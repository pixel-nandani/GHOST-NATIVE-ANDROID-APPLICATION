package com.ghost.agent.llm

/**
 * Minimal text-in/text-out contract for an on-device model.
 *
 * Deliberately narrower than any runtime's real API. Ghost needs exactly one thing
 * from a language model -- "given this prompt, give me one JSON object" -- and keeping
 * the seam this small means swapping MediaPipe for ExecuTorch (or stubbing it out
 * entirely in tests) touches one file and no logic.
 */
interface LlmEngine : AutoCloseable {

    /** Human-readable engine + model identity, shown in the overlay. */
    val describe: String

    /** Which compute unit actually served the last call: "npu", "gpu" or "cpu". */
    val backend: String

    val isReady: Boolean

    /**
     * Run one completion. Must not throw on model error -- return the raw text (or an
     * empty string) and let the caller's parser decide. A crash here would take down
     * the accessibility service mid-gesture.
     */
    suspend fun generate(prompt: String): String
}
