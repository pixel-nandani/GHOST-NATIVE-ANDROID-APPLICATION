package com.ghost.agent.core.planning

import com.ghost.agent.core.model.ParsedAction

/** Result of one planning call, with the latency number the pitch needs. */
data class PlanResult(
    val parsed: ParsedAction,
    val latencyMs: Long,
    /** e.g. "npu", "gpu", "cpu", "heuristic" -- reported live in the overlay. */
    val backend: String,
    val rawOutput: String,
)

/**
 * The only AI seam in the entire system.
 *
 * Everything else in Ghost -- reading the tree, dispatching a tap, enforcing the
 * allow-list -- is deterministic OS code. Keeping the model behind a one-method
 * interface is what makes that claim auditable, and what lets the loop be tested
 * end-to-end with a canned planner and no weights on disk.
 */
interface Planner {
    /** Human-readable name for the overlay and logs. */
    val name: String

    /** True once the underlying engine is ready to serve. */
    val isReady: Boolean

    suspend fun plan(request: PlanRequest): PlanResult

    /** Release native resources. Safe to call twice. */
    fun close() {}
}
