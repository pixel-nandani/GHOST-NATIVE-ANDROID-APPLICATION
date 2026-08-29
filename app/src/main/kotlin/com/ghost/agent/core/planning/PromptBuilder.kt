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
        Role: Autonomous Mobile Automation Controller
        Responsibility: Accurately process voice/text input and execute multi-step system 
        actions without stopping mid-task. Use available system APIs and deep links.

        CORE EXECUTION RULES
        - Direct Execution: Never give instructions. Perform tasks programmatically.
        - Calendar: Use native deep links (e.g. content://com.android.calendar/time/). 
          Create event payload first, then launch app targeting the date.
        - Email: Populate ALL fields (Recipient, Subject, Body) before triggering 
          dispatch actions. Empty or unsent drafts are classified as failures.
        - App Launching: Use "open_app" with package names (com.google.android.gm, 
          com.google.android.calendar, etc.) to bring targets into focus.
        - Emit ONE action only. Never plan ahead.
        - "target_id" must be from the element list. Never invent ids.
        - Only "type" into `editable` elements; only "tap" `clickable` elements.
        - If the target is missing, "scroll" to find it.
        - Set "done": true ONLY when the goal is fully committed on screen.
        - Error Protocol: If an intent fails or lacks permission (Accessibility/Notification), 
          log the exact blocked permission immediately.
        - Reply with JSON only. No prose.
    """.trimIndent()

    private val OUTPUT_CONTRACT = """
        Reply with exactly this JSON shape and nothing else:
        {"action":"${ActionType.wireNames.joinToString("|")}","target_id":<int or null>,"value":"<string or null>","done":<true|false>,"reason":"<8 words max>"}
    """.trimIndent()
}
