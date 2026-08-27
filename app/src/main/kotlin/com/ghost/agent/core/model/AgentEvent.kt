package com.ghost.agent.core.model

/**
 * Everything the agent loop tells the outside world. The overlay, the debug UI and
 * the latency log all consume this one stream, so there is a single source of truth
 * for "what is Ghost doing right now".
 */
sealed interface AgentEvent {
    data class TaskStarted(val goal: String, val stepCap: Int) : AgentEvent

    data class Perceived(
        val step: Int,
        val packageName: String,
        val elementCount: Int,
    ) : AgentEvent

    /** Emitted after every planning call, with the hard latency number for the pitch. */
    data class Planned(
        val step: Int,
        val action: Action,
        val latencyMs: Long,
        val backend: String,
    ) : AgentEvent

    data class Acting(val step: Int, val description: String) : AgentEvent

    data class ActionCompleted(val step: Int, val succeeded: Boolean, val detail: String?) : AgentEvent

    /** The safety layer paused execution and is waiting on the user. */
    data class AwaitingConfirmation(val step: Int, val prompt: String) : AgentEvent

    data class ConfirmationResolved(val step: Int, val approved: Boolean) : AgentEvent

    /** Planner emitted unusable output; the loop is retrying with a repair hint. */
    data class PlannerRecovered(val step: Int, val problem: String, val attempt: Int) : AgentEvent

    data class Finished(val outcome: TaskOutcome) : AgentEvent
}

/** Terminal state of one task run. */
sealed interface TaskOutcome {
    /** Model reported `done: true`. */
    data object Completed : TaskOutcome

    /** Safety layer stopped us on purpose. Not an error -- the gate working. */
    data class Blocked(val reason: String) : TaskOutcome

    /** User hit the kill switch or declined a confirmation. */
    data class Cancelled(val reason: String) : TaskOutcome

    /** Ran out of steps without reaching `done`. */
    data class StepCapReached(val cap: Int) : TaskOutcome

    /** Something genuinely broke (no screen, planner unrecoverable, node gone). */
    data class Failed(val reason: String) : TaskOutcome

    val isSuccess: Boolean get() = this is Completed

    val summary: String
        get() = when (this) {
            is Completed -> "Done"
            is Blocked -> "Stopped by safety: $reason"
            is Cancelled -> "Cancelled: $reason"
            is StepCapReached -> "Stopped after $cap steps without finishing"
            is Failed -> "Failed: $reason"
        }
}
