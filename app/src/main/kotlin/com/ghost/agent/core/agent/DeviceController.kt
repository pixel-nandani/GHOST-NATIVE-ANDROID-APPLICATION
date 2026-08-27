package com.ghost.agent.core.agent

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ScreenSnapshot

/** Result of actually performing an action on the device. */
data class ActionOutcome(
    val succeeded: Boolean,
    val detail: String? = null,
) {
    companion object {
        val Success = ActionOutcome(true)
        fun failure(detail: String) = ActionOutcome(false, detail)
    }
}

/**
 * The boundary between the agent loop and the phone.
 *
 * The loop is written entirely against this interface, which is why the whole control
 * flow -- step cap, confirmation, hallucinated-id recovery, failure backoff -- is
 * covered by JVM unit tests with a fake device and zero emulator.
 *
 * Implemented for real by
 * [com.ghost.agent.service.AccessibilityDeviceController].
 */
interface DeviceController {

    /** Capture the current screen, or null if no window content is available. */
    suspend fun snapshot(): ScreenSnapshot?

    /**
     * Perform [action]. Implementations must resolve [Action.targetId] against the
     * snapshot they most recently returned, and must not throw -- a failure is
     * reported as [ActionOutcome.succeeded] = false so the loop can decide what to do.
     */
    suspend fun perform(action: Action): ActionOutcome

    /** Give the UI time to settle after an action before the next perception pass. */
    suspend fun settle(afterAction: Action)
}

/**
 * Asks the user to approve a risky action.
 *
 * Returns true to proceed. Implementations must default to false on timeout: silence
 * is not consent when the next step might send an email.
 */
interface Confirmer {
    suspend fun confirm(prompt: String): Boolean
}

/** A [Confirmer] that approves everything. Tests and stress runs only. */
object AutoApprove : Confirmer {
    override suspend fun confirm(prompt: String): Boolean = true
}

/** A [Confirmer] that refuses everything, for testing the declined path. */
object AutoDecline : Confirmer {
    override suspend fun confirm(prompt: String): Boolean = false
}
