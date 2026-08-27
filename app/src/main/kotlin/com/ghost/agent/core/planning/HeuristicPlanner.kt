package com.ghost.agent.core.planning

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.Direction
import com.ghost.agent.core.model.ParsedAction
import com.ghost.agent.core.model.UiElement

/**
 * A deterministic, model-free planner.
 *
 * **This is not the product.** It exists for two concrete reasons:
 *
 *  1. **It unblocks hours 2-10 of the build plan.** Perception, grounding and the
 *     safety layer can be built and tested end-to-end on a real device before any
 *     model weights exist, instead of blocking on the MediaPipe integration.
 *  2. **It is the demo's dead-man's switch.** If the model fails to load on the
 *     loaner device, Ghost degrades to keyword matching instead of showing an error
 *     dialog on stage. Say so honestly if asked -- the overlay labels the backend
 *     `heuristic`, and hiding that from judges would be worse than the fallback.
 *
 * It matches goal keywords against element labels. That handles "tap Renew Now" and
 * "type the email address" and nothing subtler: it has no notion of intent, ordering,
 * or recovery. Anything requiring actual reasoning needs the real planner.
 */
class HeuristicPlanner : Planner {

    override val name: String = "Heuristic (no model)"
    override val isReady: Boolean = true

    override suspend fun plan(request: PlanRequest): PlanResult {
        val action = decide(request)
        return PlanResult(
            parsed = ParsedAction.Ok(action),
            latencyMs = 0L,
            backend = "heuristic",
            rawOutput = "<deterministic>",
        )
    }

    private fun decide(request: PlanRequest): Action {
        val goalTokens = tokenize(request.goal)
        val elements = request.snapshot.elements.filter { it.enabled }
        val alreadyTapped = request.history
            .filter { it.action.type == ActionType.TAP && it.succeeded }
            .mapNotNull { it.targetLabel?.lowercase() }
            .toSet()
        val alreadyTyped = request.history
            .filter { it.action.type == ActionType.TYPE && it.succeeded }
            .mapNotNull { it.targetLabel?.lowercase() }
            .toSet()

        // 1. Fill an empty editable field if the goal contains a plausible value.
        val emptyField = elements.firstOrNull { e ->
            e.editable && (e.label ?: "").lowercase() !in alreadyTyped
        }
        if (emptyField != null) {
            val value = extractValue(request.goal, emptyField)
            if (value != null) {
                return Action(
                    type = ActionType.TYPE,
                    targetId = emptyField.id,
                    value = value,
                    reason = "fill ${emptyField.label ?: "field"}",
                )
            }
        }

        // 2. Tap the clickable whose label best overlaps the goal and is untouched.
        val best = elements
            .filter { it.clickable && (it.label ?: "").lowercase().let { l -> l.isNotEmpty() && l !in alreadyTapped } }
            .map { it to score(it, goalTokens) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
        if (best != null) {
            return Action(
                type = ActionType.TAP,
                targetId = best.first.id,
                reason = "matches goal keyword",
            )
        }

        // 3. Nothing matched on this screen -- look further down, once.
        val scrolledLastTurn = request.history.lastOrNull()?.action?.type == ActionType.SCROLL
        if (!scrolledLastTurn && elements.any { it.scrollable }) {
            return Action(type = ActionType.SCROLL, direction = Direction.DOWN, reason = "look for target")
        }

        // 4. Out of ideas. Stop cleanly rather than flailing.
        return Action(type = ActionType.WAIT, done = true, reason = "no matching element")
    }

    /** Overlap between goal words and this element's label. */
    private fun score(element: UiElement, goalTokens: Set<String>): Int {
        val labelTokens = tokenize(element.label ?: return 0)
        return labelTokens.count { it in goalTokens }
    }

    /**
     * Pulls a literal value out of the goal for a given field.
     *
     * Only handles the two cases that show up in the scripted demo flows: an email
     * address, and text the user put in quotes. Everything else returns null, which
     * makes the planner fall through to tapping rather than typing garbage.
     */
    private fun extractValue(goal: String, field: UiElement): String? {
        val hint = (field.label ?: "").lowercase()

        if ("email" in hint || "to" == hint.trim() || "recipient" in hint) {
            EMAIL.find(goal)?.let { return it.value }
        }
        QUOTED.find(goal)?.let { return it.groupValues[1] }
        if ("subject" in hint) {
            return goal.substringAfter("subject", "").trim().trim('"', ':', ' ')
                .takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9@.]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private companion object {
        val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
        val QUOTED = Regex("[\"“']([^\"”']{2,})[\"”']")
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "then", "please", "can", "you", "will",
            "that", "this", "from", "into", "add", "get", "app",
        )
    }
}
