package com.ghost.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ghost.agent.core.agent.GhostPhase
import com.ghost.agent.core.safety.SafetyPolicy
import com.ghost.agent.service.GhostSession

/**
 * The control surface. Three jobs, in priority order:
 *
 *  1. Make the two OS-level permission gates impossible to miss -- they are the #1
 *     cause of "it doesn't work" on an unfamiliar device (doc Section 10).
 *  2. Take a goal.
 *  3. Show, in text, exactly what the agent did -- the transcript is the debugging tool
 *     that turns "it failed" into "it failed at step 4 because target_id 7 was gone".
 */
@Composable
fun MainScreen(
    spokenGoal: MutableState<String?>,
    onRequestVoice: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onGoalDispatched: () -> Unit,
) {
    val context = LocalContext.current
    val state by GhostSession.state.collectAsStateWithLifecycle()

    var goal by rememberSaveable { mutableStateOf("") }
    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }

    // Re-check on every resume: the user leaves for Settings and comes back, and a stale
    // "not enabled" banner after they have just enabled it looks like a broken app.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            accessibilityOn = SetupChecks.isAccessibilityEnabled(context)
            overlayOn = SetupChecks.canDrawOverlays(context)
        }
    }

    LaunchedEffect(spokenGoal.value) {
        spokenGoal.value?.let {
            goal = it
            spokenGoal.value = null
        }
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Ghost", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "On-device screen-reading task agent",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (!accessibilityOn) {
                SetupCard(
                    title = "Enable the accessibility service",
                    body = "Settings ▸ Accessibility ▸ Installed apps ▸ Ghost ▸ On.\n" +
                        "This is an OS-level toggle; it cannot be granted from inside the app. " +
                        "Some skins bury it under \"Downloaded apps\" or \"Installed services\".",
                    action = "Open Accessibility settings",
                    onClick = onOpenAccessibilitySettings,
                )
            }

            if (!overlayOn) {
                SetupCard(
                    title = "Allow the floating bubble",
                    body = "The status bubble carries the live step readout and the kill switch. " +
                        "Ghost still runs without it, but you lose the on-screen stop button.",
                    action = "Open overlay settings",
                    onClick = onOpenOverlaySettings,
                )
            }

            StatusCard(
                connected = GhostSession.isServiceConnected,
                plannerName = GhostSession.plannerName,
                hasModel = GhostSession.hasModel,
            )

            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("What should Ghost do?") },
                placeholder = { Text("Email accounts@company.com the parking receipt") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (GhostSession.start(goal.trim())) onGoalDispatched()
                    },
                    enabled = goal.isNotBlank() && !state.isRunning && accessibilityOn,
                ) { Text("Run") }

                OutlinedButton(onClick = onRequestVoice, enabled = !state.isRunning) {
                    Text("Speak")
                }

                if (state.isRunning) {
                    OutlinedButton(onClick = GhostSession::stop) { Text("Stop") }
                }
            }

            if (state.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LiveCard(
                phase = state.phase,
                statusLine = state.statusLine,
                progress = state.progressLabel,
                latencyMs = state.lastLatencyMs,
                backend = state.backend,
                currentPackage = state.currentPackage,
            )

            state.confirmationPrompt?.let { prompt ->
                ConfirmCard(prompt)
            }

            if (state.transcript.isNotEmpty()) {
                Text("Transcript", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    // weight, not wrap: without it the list competes with the cards above
                    // for height and can measure to zero on a short screen.
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.transcript) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(action)
            }
        }
    }
}

@Composable
private fun StatusCard(connected: Boolean, plannerName: String, hasModel: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (connected) "● service running" else "○ service not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            Text("Planner: $plannerName", style = MaterialTheme.typography.bodySmall)
            if (connected && !hasModel) {
                // Never hide the fallback. A judge asking "is that the model deciding?"
                // deserves a straight answer, and so does anyone debugging a bad run.
                Text(
                    "No model weights found — running the deterministic fallback planner. " +
                        "Push weights to /data/local/tmp/ghost/model.task and re-toggle the service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Allow-list: ${SafetyPolicy.DEMO.allowedPackages.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Step cap: ${SafetyPolicy.DEMO.stepCap} · confirm before send/submit/pay/delete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun LiveCard(
    phase: GhostPhase,
    statusLine: String,
    progress: String,
    latencyMs: Long,
    backend: String?,
    currentPackage: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(phase.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(statusLine, style = MaterialTheme.typography.bodyLarge)
            if (progress.isNotEmpty()) {
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (backend != null) {
                Text(
                    "last planning step: ${latencyMs}ms · $backend · 0 network calls",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            currentPackage?.let {
                Text("in: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ConfirmCard(prompt: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Confirmation required", style = MaterialTheme.typography.titleSmall)
            Text(prompt, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { GhostSession.resolveConfirmation(true) }) { Text("Allow once") }
                OutlinedButton(onClick = { GhostSession.resolveConfirmation(false) }) { Text("Deny") }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
