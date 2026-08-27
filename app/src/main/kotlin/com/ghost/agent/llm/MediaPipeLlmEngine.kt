package com.ghost.agent.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmEngine] backed by the MediaPipe LLM Inference API.
 *
 * ---------------------------------------------------------------------------------
 *  VERSION-SENSITIVE FILE -- this is the ONLY place MediaPipe types are referenced.
 *
 *  Pinned to `com.google.mediapipe:tasks-genai:0.10.24` (see gradle/libs.versions.toml).
 *  This API has moved between releases: sampling controls (`topK`, `temperature`) lived
 *  on `LlmInferenceOptions` in ~0.10.14-0.10.20 and moved onto a separate
 *  `LlmInferenceSession` in later builds. Only the subset that is stable across all of
 *  them is used here -- `setModelPath`, `setMaxTokens`, `createFromOptions`,
 *  `generateResponse`, `close`.
 *
 *  DAY-ONE TASK (~10 min, do it before wiring flows): pin sampling to near-greedy.
 *  Default temperature is ~0.8, which is wrong for a planner that must emit strict
 *  JSON -- it is the single biggest cause of parse retries. Depending on the version
 *  that resolves, add either
 *      .setTemperature(0.1f).setTopK(1)          // <= 0.10.20 style, on options
 *  or create an LlmInferenceSession with those values                // newer style
 *  and re-measure the parse-retry rate. Autocomplete on the builder will tell you
 *  which one you have.
 * ---------------------------------------------------------------------------------
 */
class MediaPipeLlmEngine private constructor(
    private val engine: LlmInference,
    private val modelName: String,
    override val backend: String,
) : LlmEngine {

    override val describe: String get() = "MediaPipe / $modelName ($backend)"

    override val isReady: Boolean get() = !closed

    @Volatile
    private var closed = false

    /**
     * Inference is blocking and long (hundreds of ms). It runs on [Dispatchers.Default]
     * so it never occupies the accessibility service's main thread -- blocking that
     * thread stalls gesture dispatch for the whole system, not just Ghost.
     */
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        if (closed) return@withContext ""
        try {
            engine.generateResponse(prompt) ?: ""
        } catch (e: Exception) {
            // Never propagate: the loop treats empty output as a parse failure and
            // retries with a repair hint, which is strictly better than dying.
            Log.e(TAG, "inference failed", e)
            ""
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { engine.close() }.onFailure { Log.w(TAG, "close failed", it) }
    }

    companion object {
        private const val TAG = "GhostLlm"

        /** Max tokens per planning reply. One small JSON object needs very few. */
        private const val MAX_TOKENS = 512

        /**
         * Where the engine looks for weights, in order.
         *
         * `/data/local/tmp/ghost/` first because that is adb-pushable without
         * rebuilding the APK -- during a 30-hour build you will swap models more than
         * once, and a 2GB asset in the APK makes every install take minutes.
         */
        fun candidatePaths(context: Context): List<File> = listOf(
            File("/data/local/tmp/ghost/model.task"),
            File(context.filesDir, "models/model.task"),
            File(context.getExternalFilesDir(null), "model.task"),
        )

        fun findModel(context: Context): File? =
            candidatePaths(context).firstOrNull { it.isFile && it.length() > 0 }

        /**
         * Loads the model, or returns null if there are no usable weights on device.
         *
         * Null is a normal, expected outcome -- the caller falls back to
         * [com.ghost.agent.core.planning.HeuristicPlanner] so the app still runs.
         */
        fun tryCreate(context: Context): MediaPipeLlmEngine? {
            val model = findModel(context) ?: run {
                Log.w(TAG, "no model found; searched ${candidatePaths(context).joinToString()}")
                return null
            }

            return try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()

                val started = System.currentTimeMillis()
                val inference = LlmInference.createFromOptions(context, options)
                val loadMs = System.currentTimeMillis() - started
                Log.i(TAG, "loaded ${model.name} (${model.length() / (1024 * 1024)}MB) in ${loadMs}ms")

                MediaPipeLlmEngine(
                    engine = inference,
                    modelName = model.nameWithoutExtension,
                    // MediaPipe does not report the delegate it chose. Confirm the real
                    // one from logcat (`adb logcat -s tflite:V` shows QNN/NPU delegate
                    // creation) before putting a number on the metrics slide.
                    backend = "npu/gpu (see logcat)",
                )
            } catch (e: Throwable) {
                // Throwable, not Exception: a missing native .so surfaces as
                // UnsatisfiedLinkError, and that must degrade to the fallback planner
                // rather than crash the app on launch.
                Log.e(TAG, "failed to load ${model.absolutePath}", e)
                null
            }
        }
    }
}
