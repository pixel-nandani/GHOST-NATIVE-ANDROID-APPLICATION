package com.ghost.agent.core.planning

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.perception.ScreenSerializer

/** One completed turn, replayed to the model so it does not repeat itself. */
data class HistoryEntry(
    val step: Int,
    val action: Action,
    val targetLabel: String?,
    val succeeded: Boolean,
    val detail: String? = null,
) {
    fun render(): String {
        val what = buildString {
            append(action.type.wire)
            targetLabel?.let { append(" \"$it\"") }
            action.value?.takeIf { action.type != ActionType.TAP }?.let { append(" = \"$it\"") }
        }
        val status = if (succeeded) "ok" else "FAILED${detail?.let { ": $it" } ?: ""}"
        return "$step. $what -> $status"
    }
}

/** Everything the planner needs for one decision. */
data class PlanRequest(
    val goal: String,
    val snapshot: ScreenSnapshot,
    val history: List<HistoryEntry>,
    val stepNumber: Int,
    val stepCap: Int,
    /** Set after a parse failure so the model can correct itself. */
    val repairHint: String? = null,
)

/**
 * Builds the planning prompt.
 *
 * Three constraints shape this, all of them consequences of running a 2-4B model
 * on a phone rather than a frontier model in a datacenter:
 *
 *  1. **The schema is stated twice** -- once as a contract up front, once as a
 *     literal template at the very end. Small models weight the tail of the prompt
 *     heavily, and the last thing they read should be the shape they must emit.
 *  2. **Rules are short and imperative.** Long explanatory rules get paraphrased
 *     into the output instead of followed.
 *  3. **History is compressed to one line per step.** It exists only to stop the
 *     model re-tapping something it already tapped.
 */
object PromptBuilder {

    fun build(request: PlanRequest): String = buildString {
        appendLine(SYSTEM_RULES)
        appendLine()
        appendLine("GOAL: ${request.goal}")
        appendLine()
        appendLine(renderHistory(request.history))
        appendLine()
        appendLine(ScreenSerializer.serialize(request.snapshot))
        appendLine()
        appendLine("STEP ${request.stepNumber} of at most ${request.stepCap}.")

        request.repairHint?.let {
            appendLine()
            appendLine("YOUR LAST REPLY WAS REJECTED. $it")
        }

        appendLine()
        append(OUTPUT_CONTRACT)
    }

    private fun renderHistory(history: List<HistoryEntry>): String =
        if (history.isEmpty()) {
            "ACTIONS SO FAR: (none -- this is the first step)"
        } else {
            buildString {
                appendLine("ACTIONS SO FAR:")
                // Only the last 8 turns: older context stops earning its tokens and
                // pushes the element list out of the model's effective window.
                history.takeLast(8).forEach { appendLine(it.render()) }
            }.trimEnd()
        }

    private val SYSTEM_RULES = """
        You control an Android phone. You are given a goal and the elements currently
        on screen. Choose the single next action that makes progress toward the goal.

        RULES
        - Emit ONE action only. Never plan ahead, never emit a list.
        - "target_id" must be an id from the element list above. Never invent an id.
        - Only "type" into elements marked `editable`.
        - Only "tap" elements marked `clickable`.
        - If the element you need is not listed, "scroll" to look for it.
        - If the screen looks like it is still loading, "wait".
        - Set "done": true only when the goal is fully achieved on screen.
        - Reply with JSON only. No prose, no markdown, no code fence.
    """.trimIndent()

    private val OUTPUT_CONTRACT = """
        Reply with exactly this JSON shape and nothing else:
        {"action":"${ActionType.wireNames.joinToString("|")}","target_id":<int or null>,"value":"<string or null>","done":<true|false>,"reason":"<8 words max>"}
    """.trimIndent()
}
