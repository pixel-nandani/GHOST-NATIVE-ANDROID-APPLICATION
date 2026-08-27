package com.ghost.agent.core.model

/**
 * On-screen rectangle, in absolute screen pixels.
 *
 * Deliberately a plain data class rather than [android.graphics.Rect] so the whole
 * perception/planning core stays Android-free and unit-testable on the JVM.
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** A node with no area cannot be tapped, however clickable it claims to be. */
    val isTappable: Boolean get() = width > 0 && height > 0

    override fun toString(): String = "($left,$top,$right,$bottom)"
}

/**
 * One interactable or informative element from the current screen.
 *
 * [id] is assigned fresh on every perception pass and is only valid for the snapshot
 * it came from. This is why the loop re-reads the screen before every single action
 * instead of caching ids across turns.
 */
data class UiElement(
    val id: Int,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
) {
    /** Best human-readable handle for this element, preferring visible text. */
    val label: String?
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** Short class name, e.g. `android.widget.Button` -> `Button`. */
    val shortClass: String get() = className.substringAfterLast('.')

    /**
     * Whether this element is worth spending prompt tokens on. Decorative
     * containers with no label and no affordance are noise to the planner.
     */
    val isInteresting: Boolean
        get() = bounds.isTappable &&
            (clickable || editable || scrollable || checkable || !label.isNullOrBlank())
}

/**
 * A single perception pass: everything the agent knows about the world right now.
 */
data class ScreenSnapshot(
    val packageName: String,
    val elements: List<UiElement>,
    val windowTitle: String? = null,
    /** Monotonic timestamp (ms) of capture, for latency accounting. */
    val capturedAtMs: Long = 0L,
) {
    fun elementById(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    val isEmpty: Boolean get() = elements.isEmpty()
}
