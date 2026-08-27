package com.ghost.agent.core.safety

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.model.UiElement

/**
 * Enforces [SafetyPolicy] on every action, before it reaches the OS.
 *
 * This runs on all four checks every single turn -- not once at the start of a task.
 * A step-at-a-time agent can drift into a new app or a new screen between any two
 * actions, so a check performed once at the beginning has already gone stale by the
 * second action.
 *
 * Pure and synchronous by design: no coroutines, no Android types, no I/O. The gate
 * must be trivially auditable, because it is the only thing standing between a small
 * quantized model's opinion and a real tap on a real "Pay" button.
 */
class SafetyGate(val policy: SafetyPolicy) {

    /** Precompiled word-boundary matchers, one per keyword. */
    private val keywordPatterns: List<Pair<String, Regex>> = policy.riskyKeywords.map { kw ->
        kw to Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE)
    }

    /**
     * May Ghost act inside the app currently on screen?
     *
     * Fails closed: an unknown or blank package is refused.
     */
    fun checkPackage(packageName: String?): PackageVerdict {
        if (packageName.isNullOrBlank()) {
            return PackageVerdict.Refused("<unknown>", "could not identify the foreground app")
        }
        if (packageName !in policy.allowedPackages) {
            return PackageVerdict.Refused(
                packageName,
                "$packageName is not on the allow-list",
            )
        }
        return PackageVerdict.Allowed
    }

    /** Has this task used up its step budget? */
    fun isWithinStepCap(stepNumber: Int): Boolean = stepNumber <= policy.stepCap

    /**
     * Classifies one action against the live screen.
     *
     * Order matters: structural validity is checked before risk, so a hallucinated
     * `target_id` is rejected outright rather than surfaced to the user as a
     * confirmation prompt about an element that does not exist.
     */
    fun classify(action: Action, snapshot: ScreenSnapshot): RiskVerdict {
        val target = action.targetId?.let { snapshot.elementById(it) }

        // --- structural validity -------------------------------------------------
        if (action.needsTarget) {
            if (action.targetId == null) {
                return RiskVerdict.Reject("${action.type.wire} requires a target_id")
            }
            if (target == null) {
                return RiskVerdict.Reject(
                    "target_id ${action.targetId} is not on the current screen",
                )
            }
            if (!target.bounds.isTappable) {
                return RiskVerdict.Reject("element ${action.targetId} has zero size")
            }
            if (!target.enabled) {
                return RiskVerdict.Reject("element ${action.targetId} is disabled")
            }
        }

        if (action.type == ActionType.TYPE && target != null && !target.editable) {
            return RiskVerdict.Reject(
                "element ${action.targetId} (${target.shortClass}) is not editable",
            )
        }

        if (action.type == ActionType.OPEN_APP) {
            val pkg = action.value
            if (pkg.isNullOrBlank()) {
                return RiskVerdict.Reject("open_app requires a package name")
            }
            if (pkg !in policy.allowedPackages) {
                return RiskVerdict.Reject("$pkg is not on the allow-list")
            }
        }

        // --- risk ----------------------------------------------------------------
        if (!policy.requireConfirmForRisky) return RiskVerdict.Proceed

        val riskySurface = riskySurfaceFor(action, target) ?: return RiskVerdict.Proceed
        val matched = keywordPatterns.firstOrNull { it.second.containsMatchIn(riskySurface) }
            ?: return RiskVerdict.Proceed

        return RiskVerdict.NeedsConfirmation(
            prompt = buildPrompt(action, target, matched.first),
            matchedKeyword = matched.first,
        )
    }

    /**
     * The text that gets keyword-scanned for a given action.
     *
     * Only *committing* gestures are scanned. Typing the word "delete" into a search
     * box is not a destructive act, and prompting there would train the user to
     * dismiss confirmations without reading them -- which costs more safety than it
     * buys.
     */
    private fun riskySurfaceFor(action: Action, target: UiElement?): String? = when (action.type) {
        ActionType.TAP -> listOfNotNull(target?.text, target?.contentDescription, target?.viewId)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        // A bare `done` is harmless; a tap is what commits.
        ActionType.TYPE, ActionType.SCROLL, ActionType.SWIPE,
        ActionType.WAIT, ActionType.BACK, ActionType.OPEN_APP -> null
    }

    private fun buildPrompt(action: Action, target: UiElement?, keyword: String): String {
        val label = target?.label ?: "this control"
        return "Ghost wants to tap \"$label\". This looks like it will $keyword " +
            "something and may not be undoable. Allow it?"
    }
}
