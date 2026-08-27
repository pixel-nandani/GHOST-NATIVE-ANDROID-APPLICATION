package com.ghost.agent.core.agent

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.planning.ActionParser
import com.ghost.agent.core.planning.PlanRequest
import com.ghost.agent.core.planning.PlanResult
import com.ghost.agent.core.planning.Planner

/**
 * A [DeviceController] driven by a script of snapshots.
 *
 * Advances to the next snapshot after every successful action, which models the real
 * thing closely enough to exercise the loop: the agent sees a new screen each turn and
 * can never rely on ids from a previous one. When the script runs out, the last snapshot
 * repeats -- so a loop that fails to terminate will hit the step cap rather than hang.
 */
class FakeDevice(
    private val script: List<ScreenSnapshot?>,
    private val failActions: Set<Int> = emptySet(),
) : DeviceController {

    val performed = mutableListOf<Action>()
    var settleCount = 0
        private set

    private var index = 0

    override suspend fun snapshot(): ScreenSnapshot? =
        script.getOrNull(index) ?: script.lastOrNull()

    override suspend fun perform(action: Action): ActionOutcome {
        performed += action
        val step = performed.size
        return if (step in failActions) {
            ActionOutcome.failure("scripted failure at action $step")
        } else {
            index++
            ActionOutcome.Success
        }
    }

    override suspend fun settle(afterAction: Action) {
        settleCount++
    }
}

/**
 * A [Planner] that replays canned model output, verbatim, through the real
 * [ActionParser].
 *
 * Going through the real parser rather than handing the loop pre-built [Action]s is the
 * point: it means these tests cover the malformed-output recovery path with exactly the
 * text a real model produces.
 */
class ScriptedPlanner(
    private val outputs: List<String>,
    private val latencyMs: Long = 350L,
) : Planner {

    override val name: String = "Scripted"
    override val isReady: Boolean = true

    var callCount = 0
        private set

    /** Prompts the loop actually sent, for asserting on repair hints. */
    val prompts = mutableListOf<String>()

    override suspend fun plan(request: PlanRequest): PlanResult {
        val raw = outputs.getOrNull(callCount) ?: outputs.lastOrNull() ?: ""
        callCount++
        request.repairHint?.let { prompts += it }
        return PlanResult(
            parsed = ActionParser.parse(raw),
            latencyMs = latencyMs,
            backend = "test",
            rawOutput = raw,
        )
    }
}
