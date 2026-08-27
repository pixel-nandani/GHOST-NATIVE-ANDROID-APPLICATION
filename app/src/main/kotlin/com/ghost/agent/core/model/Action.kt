package com.ghost.agent.core.model

/**
 * The action vocabulary the planning model may emit.
 *
 * `TAP`/`TYPE`/`SCROLL`/`SWIPE`/`WAIT` come straight from Appendix A of the design
 * doc. `BACK` and `OPEN_APP` are additions: without them a step-at-a-time loop has
 * no legal way to leave a dead-end screen or cross from one app to the next, which
 * the headline two-app demo flow requires. Both are still gated by the allow-list.
 */
enum class ActionType(val wire: String) {
    TAP("tap"),
    TYPE("type"),
    SCROLL("scroll"),
    SWIPE("swipe"),
    WAIT("wait"),
    BACK("back"),
    OPEN_APP("open_app"),
    ;

    companion object {
        fun fromWire(raw: String?): ActionType? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == key }
        }

        val wireNames: List<String> get() = entries.map { it.wire }
    }
}

/** Direction for [ActionType.SCROLL] and [ActionType.SWIPE]. */
enum class Direction(val wire: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromWire(raw: String?): Direction? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == key }
        }
    }
}

/**
 * One validated decision from the planner. Exactly one action per loop turn.
 *
 * Construct these only via [com.ghost.agent.core.planning.ActionParser] or in tests;
 * reaching this type means the shape is already known-good, but *not* that
 * [targetId] exists on the current screen -- the loop validates that separately
 * against the live snapshot, because the model can and does hallucinate ids.
 */
data class Action(
    val type: ActionType,
    val targetId: Int? = null,
    val value: String? = null,
    val direction: Direction? = null,
    val done: Boolean = false,
    /** Model's own short rationale, surfaced in the overlay. Never trusted logically. */
    val reason: String? = null,
) {
    /** True when this action needs to resolve to a real node on screen. */
    val needsTarget: Boolean
        get() = type == ActionType.TAP || type == ActionType.TYPE

    fun describe(target: UiElement? = null): String {
        val name = target?.label?.let { "\"$it\"" } ?: targetId?.let { "#$it" } ?: ""
        return when (type) {
            ActionType.TAP -> "Tapping $name"
            ActionType.TYPE -> "Typing into $name"
            ActionType.SCROLL -> "Scrolling ${direction?.wire ?: "down"}"
            ActionType.SWIPE -> "Swiping ${direction?.wire ?: "up"}"
            ActionType.WAIT -> "Waiting for the screen to settle"
            ActionType.BACK -> "Going back"
            ActionType.OPEN_APP -> "Opening ${value ?: "app"}"
        }.trim()
    }
}

/** Outcome of asking the parser to turn raw model text into an [Action]. */
sealed interface ParsedAction {
    data class Ok(val action: Action) : ParsedAction

    /**
     * The model produced something unusable. [repairHint] is fed back into the next
     * planning call so the loop can self-correct instead of aborting the task.
     */
    data class Invalid(
        val reason: String,
        val raw: String,
        val repairHint: String,
    ) : ParsedAction
}
