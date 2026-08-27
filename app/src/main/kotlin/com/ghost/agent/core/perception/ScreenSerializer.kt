package com.ghost.agent.core.perception

import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.model.UiElement

/**
 * Turns a [ScreenSnapshot] into the compact text block the planner reads.
 *
 * This is the single most leverage-dense file in the project. Every token spent here
 * is a token the small on-device model has to chew through before it can decide
 * anything, and every element omitted is an element it cannot possibly tap. The
 * format matches Appendix B of the design doc.
 */
object ScreenSerializer {

    /**
     * Hard ceiling on serialized elements. A Gmail inbox can expose 200+ nodes; a
     * 3.8B model given all of them will reliably lose the thread. Ranking (below)
     * decides which survive the cut, and truncation is always announced in-band so
     * the model knows it may need to scroll rather than assuming it saw everything.
     */
    const val MAX_ELEMENTS: Int = 40

    /** Labels longer than this are truncated -- email bodies must not eat the prompt. */
    const val MAX_LABEL_CHARS: Int = 80

    /**
     * Filters, ranks and renders the snapshot.
     *
     * Ranking is by interaction value, not tree order: editable fields first (the
     * agent is usually mid-form), then clickables, then everything else. Within a
     * tier, original order is preserved so reading order still makes sense.
     */
    fun serialize(snapshot: ScreenSnapshot, maxElements: Int = MAX_ELEMENTS): String {
        if (snapshot.isEmpty) {
            return "CURRENT SCREEN: (empty -- no accessibility nodes exposed by ${snapshot.packageName})"
        }

        val ranked = rank(snapshot.elements)
        val shown = ranked.take(maxElements)
        val omitted = ranked.size - shown.size

        return buildString {
            append("CURRENT SCREEN (app: ${snapshot.packageName}")
            snapshot.windowTitle?.takeIf { it.isNotBlank() }?.let { append(", window: \"$it\"") }
            appendLine("):")
            // Re-sort the surviving elements back into id order: the model matches on
            // ids, and a monotonic list is far easier for it to index into.
            shown.sortedBy { it.id }.forEach { appendLine(render(it)) }
            if (omitted > 0) {
                appendLine("... $omitted more element(s) not shown -- scroll to reveal them.")
            }
        }.trimEnd()
    }

    /** Elements the planner is allowed to reference, in prompt-priority order. */
    fun rank(elements: List<UiElement>): List<UiElement> =
        elements.filter { it.isInteresting && it.enabled }
            .sortedByDescending { priority(it) }

    private fun priority(e: UiElement): Int = when {
        e.editable && e.focused -> 5
        e.editable -> 4
        e.clickable -> 3
        e.checkable -> 2
        e.scrollable -> 1
        else -> 0
    }

    /** `[2] EditText "Vehicle number" editable bounds=(40,140,340,190)` */
    fun render(e: UiElement): String = buildString {
        append("[${e.id}] ")
        append(e.shortClass)
        e.label?.let { append(" \"${truncate(it)}\"") }
        if (e.clickable) append(" clickable")
        if (e.editable) append(" editable")
        if (e.scrollable) append(" scrollable")
        if (e.checkable) append(if (e.checked) " checked" else " unchecked")
        append(" bounds=${e.bounds}")
    }

    private fun truncate(s: String): String {
        val clean = s.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_LABEL_CHARS) clean else clean.take(MAX_LABEL_CHARS - 1) + "…"
    }
}
