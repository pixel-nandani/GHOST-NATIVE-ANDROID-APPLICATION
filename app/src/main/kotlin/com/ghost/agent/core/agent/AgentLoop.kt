package com.ghost.agent.core.agent

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.AgentEvent
import com.ghost.agent.core.model.ParsedAction
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.model.TaskOutcome
import com.ghost.agent.core.planning.HistoryEntry
import com.ghost.agent.core.planning.PlanRequest
import com.ghost.agent.core.planning.Planner
import com.ghost.agent.core.safety.PackageVerdict
import com.ghost.agent.core.safety.RiskVerdict
import com.ghost.agent.core.safety.SafetyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The step-at-a-time agent loop: perceive -> plan -> gate -> act -> repeat.
 *
 * The single most important property here is that **nothing is cached across turns**.
 * Element ids, the package name, whether a button is enabled -- all of it is re-read
 * before every action. That is what lets the loop absorb a popup, a slow load, or a
 * keyboard sliding up over the field it was about to tap, without any special-casing
 * for those situations. An upfront plan cannot do this; it just keeps executing stale
 * decisions into a screen that has moved on.
 *
 * Cancellation is the kill switch. Cancelling the coroutine running [run] stops Ghost
 * between any two operations, which is why [ensureActive] is called at the top of
 * every turn and before the confirmation await.
 */
class AgentLoop(
    private val device: DeviceController,
    private val planner: Planner,
    private val gate: SafetyGate,
    private val confirmer: Confirmer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stepLog: StepLog = StepLog(),
    private val onEvent: (AgentEvent) -> Unit = {},
) {

    /** Retries allowed per step when the planner emits unusable output. */
    private val maxPlanRepairs = 2

    val timings: StepLog get() = stepLog

    suspend fun run(goal: String): TaskOutcome {
        stepLog.clear()
        onEvent(AgentEvent.TaskStarted(goal, gate.policy.stepCap))
        return try {
            val outcome = execute(goal)
            onEvent(AgentEvent.Finished(outcome))
            outcome
        } catch (e: CancellationException) {
            // Kill switch. Report it, then let cancellation propagate so the caller's
            // scope really does tear down instead of silently continuing.
            onEvent(AgentEvent.Finished(TaskOutcome.Cancelled("stopped by kill switch")))
            throw e
        }
    }

    private suspend fun execute(goal: String): TaskOutcome {
        val policy = gate.policy
        val history = mutableListOf<HistoryEntry>()
        var consecutiveFailures = 0
        var step = 1

        while (true) {
            currentCoroutineContext().ensureActive()

            if (!gate.isWithinStepCap(step)) {
                return TaskOutcome.StepCapReached(policy.stepCap)
            }

            // --- 1. PERCEPTION ---------------------------------------------------
            val perceptionStart = clock()
            val snapshot = device.snapshot()
                ?: return TaskOutcome.Failed("no window content available")
            val perceptionMs = clock() - perceptionStart

            onEvent(AgentEvent.Perceived(step, snapshot.packageName, snapshot.elements.size))

            // Checked every turn, not once: the agent can drift between apps mid-task,
            // so an allow-list checked only at task start is already stale by step two.
            when (val verdict = gate.checkPackage(snapshot.packageName)) {
                is PackageVerdict.Refused -> return TaskOutcome.Blocked(verdict.reason)
                PackageVerdict.Allowed -> Unit
            }

            if (snapshot.isEmpty) {
                // Canvas-rendered or still-loading screen. Give it one wait, then give
                // up rather than spinning on a tree that will never populate.
                if (history.lastOrNull()?.action?.type == ActionType.WAIT) {
                    return TaskOutcome.Failed(
                        "${snapshot.packageName} exposes no accessibility nodes",
                    )
                }
                val wait = Action(ActionType.WAIT, reason = "screen not ready")
                device.settle(wait)
                history += HistoryEntry(step, wait, null, succeeded = true)
                step++
                continue
            }

            // --- 2. PLANNING -----------------------------------------------------
            val plan = planWithRepair(goal, snapshot, history, step, policy.stepCap)
                ?: return TaskOutcome.Failed(
                    "planner produced no usable action after $maxPlanRepairs retries",
                )
            val action = plan.action
            onEvent(AgentEvent.Planned(step, action, plan.latencyMs, plan.backend))

            // --- 3. SAFETY -------------------------------------------------------
            when (val risk = gate.classify(action, snapshot)) {
                is RiskVerdict.Reject -> {
                    // Almost always a hallucinated target_id. Re-perceive and let the
                    // model try again against a fresh element list -- this is the
                    // fail-safe recovery path doc Section 10 asks for.
                    consecutiveFailures++
                    history += HistoryEntry(
                        step, action, targetLabel = null,
                        succeeded = false, detail = risk.reason,
                    )
                    onEvent(AgentEvent.ActionCompleted(step, false, risk.reason))
                    if (consecutiveFailures >= policy.maxConsecutiveFailures) {
                        return TaskOutcome.Failed(
                            "$consecutiveFailures invalid actions in a row: ${risk.reason}",
                        )
                    }
                    step++
                    continue
                }

                is RiskVerdict.NeedsConfirmation -> {
                    onEvent(AgentEvent.AwaitingConfirmation(step, risk.prompt))
                    currentCoroutineContext().ensureActive()
                    val approved = confirmer.confirm(risk.prompt)
                    onEvent(AgentEvent.ConfirmationResolved(step, approved))
                    if (!approved) {
                        return TaskOutcome.Cancelled("you declined: ${risk.matchedKeyword}")
                    }
                }

                RiskVerdict.Proceed -> Unit
            }

            // --- 4. ACTION -------------------------------------------------------
            val target = action.targetId?.let { snapshot.elementById(it) }
            onEvent(AgentEvent.Acting(step, action.describe(target)))

            val actionStart = clock()
            val result = device.perform(action)
            device.settle(action)
            val actionMs = clock() - actionStart

            onEvent(AgentEvent.ActionCompleted(step, result.succeeded, result.detail))
            history += HistoryEntry(step, action, target?.label, result.succeeded, result.detail)
            stepLog.record(StepTiming(step, perceptionMs, plan.latencyMs, actionMs, plan.backend))

            consecutiveFailures = if (result.succeeded) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= policy.maxConsecutiveFailures) {
                return TaskOutcome.Failed(
                    "$consecutiveFailures actions failed in a row: ${result.detail ?: "unknown"}",
                )
            }

            // `done` is honored only after the action lands, so the model can mark the
            // final tap and task completion in a single turn.
            if (action.done) return TaskOutcome.Completed

            step++
        }
    }

    /** One usable action, plus its latency and backend. */
    private data class ResolvedPlan(val action: Action, val latencyMs: Long, val backend: String)

    /**
     * Calls the planner, retrying with an explicit repair hint when the output does
     * not parse.
     *
     * Latency accumulates across retries on purpose -- the metrics slide should report
     * what the step actually cost the user, including the model's false starts.
     */
    private suspend fun planWithRepair(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<HistoryEntry>,
        step: Int,
        stepCap: Int,
    ): ResolvedPlan? {
        var hint: String? = null
        var accumulatedMs = 0L

        repeat(maxPlanRepairs + 1) { attempt ->
            currentCoroutineContext().ensureActive()

            val result = planner.plan(
                PlanRequest(
                    goal = goal,
                    snapshot = snapshot,
                    history = history,
                    stepNumber = step,
                    stepCap = stepCap,
                    repairHint = hint,
                ),
            )
            accumulatedMs += result.latencyMs

            when (val parsed = result.parsed) {
                is ParsedAction.Ok ->
                    return ResolvedPlan(parsed.action, accumulatedMs, result.backend)

                is ParsedAction.Invalid -> {
                    hint = parsed.repairHint
                    onEvent(AgentEvent.PlannerRecovered(step, parsed.reason, attempt + 1))
                }
            }
        }
        return null
    }
}
