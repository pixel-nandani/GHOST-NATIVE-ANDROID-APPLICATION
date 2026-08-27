package com.ghost.agent.core.agent

import com.ghost.agent.core.model.AgentEvent
import com.ghost.agent.core.model.TaskOutcome

/** Coarse phase of the current task, for the overlay and the debug UI. */
enum class GhostPhase { IDLE, PERCEIVING, PLANNING, ACTING, AWAITING_CONFIRMATION, FINISHED }

/**
 * Everything the UI needs to render, derived from the [AgentEvent] stream.
 *
 * Kept as one immutable value so the overlay and the in-app screen cannot disagree
 * about what Ghost is doing -- during a live demo, two views showing different states
 * is worse than showing none.
 */
data class GhostState(
    val phase: GhostPhase = GhostPhase.IDLE,
    val goal: String? = null,
    val step: Int = 0,
    val stepCap: Int = 0,
    /** Plain-language line shown in the bubble, e.g. `Tapping "Renew Now"`. */
    val statusLine: String = "Idle",
    val currentPackage: String? = null,
    val lastLatencyMs: Long = 0,
    val backend: String? = null,
    val confirmationPrompt: String? = null,
    val outcome: TaskOutcome? = null,
    val transcript: List<String> = emptyList(),
) {
    val isRunning: Boolean
        get() = phase != GhostPhase.IDLE && phase != GhostPhase.FINISHED

    /** `Step 4/15` for the bubble. Empty while idle. */
    val progressLabel: String
        get() = if (stepCap > 0 && isRunning) "Step $step/$stepCap" else ""
}

/**
 * Folds one [AgentEvent] into the current [GhostState].
 *
 * A pure reducer, so the whole UI-facing state machine is testable without an emulator
 * or a running accessibility service.
 */
fun GhostState.reduce(event: AgentEvent): GhostState = when (event) {
    is AgentEvent.TaskStarted -> GhostState(
        phase = GhostPhase.PERCEIVING,
        goal = event.goal,
        stepCap = event.stepCap,
        statusLine = "Starting…",
        transcript = listOf("GOAL: ${event.goal}"),
    )

    is AgentEvent.Perceived -> copy(
        phase = GhostPhase.PLANNING,
        step = event.step,
        currentPackage = event.packageName,
        statusLine = "Reading screen (${event.elementCount} elements)…",
    )

    is AgentEvent.Planned -> copy(
        phase = GhostPhase.ACTING,
        lastLatencyMs = event.latencyMs,
        backend = event.backend,
        statusLine = event.action.reason ?: "Deciding…",
        transcript = transcript + "${event.step}. plan: ${event.action.type.wire}" +
            (event.action.targetId?.let { " #$it" } ?: "") +
            " (${event.latencyMs}ms, ${event.backend})",
    )

    is AgentEvent.Acting -> copy(
        phase = GhostPhase.ACTING,
        statusLine = event.description,
    )

    is AgentEvent.ActionCompleted -> copy(
        transcript = transcript + "${event.step}. ${if (event.succeeded) "ok" else "FAILED"}" +
            (event.detail?.let { ": $it" } ?: ""),
    )

    is AgentEvent.AwaitingConfirmation -> copy(
        phase = GhostPhase.AWAITING_CONFIRMATION,
        statusLine = "Waiting for your confirmation",
        confirmationPrompt = event.prompt,
    )

    is AgentEvent.ConfirmationResolved -> copy(
        phase = GhostPhase.ACTING,
        confirmationPrompt = null,
        transcript = transcript + "${event.step}. you ${if (event.approved) "approved" else "declined"}",
    )

    is AgentEvent.PlannerRecovered -> copy(
        statusLine = "Model output rejected, retrying (${event.attempt})…",
        transcript = transcript + "   ! ${event.problem} -> retry ${event.attempt}",
    )

    is AgentEvent.Finished -> copy(
        phase = GhostPhase.FINISHED,
        outcome = event.outcome,
        confirmationPrompt = null,
        statusLine = event.outcome.summary,
        transcript = transcript + "-- ${event.outcome.summary}",
    )
}
