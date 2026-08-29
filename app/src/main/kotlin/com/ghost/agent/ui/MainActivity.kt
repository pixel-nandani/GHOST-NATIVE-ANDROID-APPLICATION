package com.ghost.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * The only Activity: setup status, goal entry, and a live view of the agent.
 *
 * The Activity does not run the agent. It hands a goal string to
 * [com.ghost.agent.service.GhostSession] and then goes to the background -- by design,
 * since the whole point is that Ghost acts inside *other* apps. Everything it renders
 * after that is a mirror of session state.
 */
class MainActivity : ComponentActivity() {

    private val spokenGoal = mutableStateOf<String?>(null)

    /**
     * Voice entry via the system recognizer.
     *
     * Uses `RecognizerIntent` rather than a bundled Whisper build: it needs no extra
     * weights, no RECORD_AUDIO handling of our own, and typed input remains the primary
     * path. Doc Section 5 lists voice as optional, and this is the version that cannot
     * cost demo time. (Note it may use Google's cloud recognizer, so the offline claim
     * covers the *agent loop*, not this optional input path -- say that plainly rather
     * than overstating it.)
     */
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            spokenGoal.value = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GhostTheme {
                MainScreen(
                    spokenGoal = spokenGoal,
                    onRequestVoice = ::launchVoiceInput,
                    onOpenAccessibilitySettings = {
                        startActivity(SetupChecks.accessibilitySettingsIntent())
                    },
                    onOpenOverlaySettings = {
                        startActivity(SetupChecks.overlaySettingsIntent(this))
                    },
                    onGoalDispatched = {
                        // Stay in the app so user can see progress cards
                    },
                )
            }
        }
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What should Ghost do?")
        }
        runCatching { voiceLauncher.launch(intent) }
    }
}
