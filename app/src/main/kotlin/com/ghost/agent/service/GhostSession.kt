package com.ghost.agent.service

import com.ghost.agent.core.agent.Confirmer
import com.ghost.agent.core.agent.GhostState
import com.ghost.agent.core.agent.reduce
import com.ghost.agent.core.model.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single rendezvous point between the Activity (which the user types into) and the
 * AccessibilityService (which owns the agent loop).
 *
 * A process-wide singleton, which is a real tradeoff: it is untestable in isolation and
 * would be a service locator + DI graph in a production app. It is the right call here
 * because the two ends genuinely cannot reach each other any other way -- the OS owns
 * the service's lifecycle, so the Activity can never hold a reference to it -- and the
 * alternatives (a bound Service with a Binder, or a local broadcast protocol) are a lot
 * of ceremony for one goal string and one boolean. Both halves live in one process.
 */
object GhostSession : Confirmer {

    private val _state = MutableStateFlow(GhostState())
    val state: StateFlow<GhostState> = _state.asStateFlow()

    /** Set by the service in onServiceConnected, cleared on unbind. */
    @Volatile
    private var engine: GhostEngine? = null

    val isServiceConnected: Boolean get() = engine != null

    /** Description of the active planner, e.g. "MediaPipe / phi3-mini (npu)". */
    val plannerName: String get() = engine?.plannerName ?: "not connected"

    /** True when the loop is running on real weights rather than the fallback. */
    val hasModel: Boolean get() = engine?.hasModel ?: false

    // ------------------------------------------------------------------ wiring

    internal fun attach(engine: GhostEngine) {
        this.engine = engine
    }

    internal fun detach() {
        engine = null
        cancelPendingConfirmation()
        _state.value = GhostState()
    }

    // ------------------------------------------------------------------ control

    /**
     * Returns false when the accessibility service is not enabled yet -- the caller
     * should send the user to Settings rather than silently doing nothing.
     */
    fun start(goal: String): Boolean {
        val target = engine ?: return false
        target.startTask(goal)
        return true
    }

    /** The kill switch. Cancels the loop's coroutine between any two operations. */
    fun stop() {
        cancelPendingConfirmation()
        engine?.stopTask()
    }

    /** Called by the loop for every event; folds into [state]. */
    internal fun publish(event: AgentEvent) {
        _state.update { it.reduce(event) }
    }

    fun clearFinishedState() {
        if (!_state.value.isRunning) _state.value = GhostState()
    }

    // ------------------------------------------------- confirm-before-submit gate

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Suspends the agent loop until the user taps allow or deny in the overlay.
     *
     * **Times out to `false`.** Silence is not consent when the next action might send
     * an email or complete a payment. The timeout is generous enough for a user to read
     * the prompt, but bounded so a forgotten dialog cannot leave the loop wedged.
     */
    override suspend fun confirm(prompt: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return try {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            pending = null
        }
    }

    /** Called from the overlay's allow/deny buttons. */
    fun resolveConfirmation(approved: Boolean) {
        pending?.complete(approved)
    }

    private fun cancelPendingConfirmation() {
        pending?.complete(false)
        pending = null
    }

    private const val CONFIRM_TIMEOUT_MS = 60_000L
}

/**
 * What [GhostSession] needs from the accessibility service. Declared here so the session
 * does not depend on the service class directly.
 *
 * Public, not internal: [GhostAccessibilityService] is public (the OS instantiates it
 * from the manifest), and Kotlin forbids a public class from implementing an internal
 * interface.
 */
interface GhostEngine {
    val plannerName: String
    val hasModel: Boolean
    fun startTask(goal: String)
    fun stopTask()
}
