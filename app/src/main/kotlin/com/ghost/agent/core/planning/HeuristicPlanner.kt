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
        val goalLower = request.goal.lowercase()
        val goalTokens = tokenize(request.goal).toMutableSet()
        
        // Boost core intent tokens
        if ("email" in goalLower || "gmail" in goalLower) goalTokens += setOf("send", "to", "subject", "message", "compose", "gmail")
        if ("calendar" in goalLower || "event" in goalLower) goalTokens += setOf("save", "add", "title", "date", "event", "calendar", "done")

        val elements = request.snapshot.elements.filter { it.enabled }
        val currentPackage = request.snapshot.packageName
        
        val history = request.history
        val alreadyTapped = history.filter { it.action.type == ActionType.TAP && it.succeeded }.mapNotNull { it.targetLabel?.lowercase() }.toSet()
        val alreadyTyped = history.filter { it.action.type == ActionType.TYPE && it.succeeded }.mapNotNull { it.targetLabel?.lowercase() }.toSet()

        // 1. App switching: Detect intent to open Gmail/Calendar
        val targetUri = when {
            ("email" in goalLower || "gmail" in goalLower) && 
                !currentPackage.contains("gm") && !currentPackage.contains("email") -> {
                val to = EMAIL.find(request.goal)?.value ?: ""
                val body = goalLower.substringAfter("saying", "").substringAfter("message", "").trim().trim('"', ' ')
                "mailto:$to?subject=Automated%20Task&body=${android.net.Uri.encode(body)}"
            }
            ("calendar" in goalLower || "event" in goalLower) && 
                !currentPackage.contains("calendar") -> {
                val title = extractValue(request.goal, UiElement(0, "", text = "title", bounds = com.ghost.agent.core.model.Bounds(0,0,0,0))) ?: "New Event"
                val date = extractValue(request.goal, UiElement(0, "", text = "date", bounds = com.ghost.agent.core.model.Bounds(0,0,0,0)))
                "ghost://calendar/create?title=${android.net.Uri.encode(title)}${date?.let { "&date=$it" } ?: ""}"
            }
            else -> null
        }

        if (targetUri != null && history.none { it.action.value == targetUri }) {
            return Action(ActionType.OPEN_APP, value = targetUri, reason = "Opening target app")
        }

        // 2. Data Entry: Find editable fields and fill them
        val editableFields = elements.filter { it.editable }
        for (field in editableFields) {
            val fieldLabel = (field.label ?: "").lowercase()
            if (fieldLabel.isEmpty()) continue
            
            val value = extractValue(request.goal, field)
            if (value != null && (field.text.isNullOrBlank() || field.text == field.label)) {
                // If it's a field we haven't typed into yet
                if (fieldLabel !in alreadyTyped) {
                    return Action(ActionType.TYPE, targetId = field.id, value = value, reason = "Typing into $fieldLabel")
                }
            }
        }

        // 3. Navigation/Commit: Find buttons to tap
        // Prioritize "Send" / "Save" / "Add" buttons
        val commitButton = elements.firstOrNull { e ->
            val label = (e.label ?: "").lowercase()
            e.clickable && ("send" in label || "save" in label || "add" in label || "done" in label || "check" in label) && label !in alreadyTapped
        }
        if (commitButton != null) {
            return Action(ActionType.TAP, targetId = commitButton.id, reason = "Committing: ${commitButton.label}")
        }

        // 4. Secondary Tapping: Matches other goal tokens
        val nextBest = elements.firstOrNull { e ->
            e.clickable && score(e, goalTokens) > 0 && (e.label ?: "").lowercase() !in alreadyTapped
        }
        if (nextBest != null) {
            return Action(ActionType.TAP, targetId = nextBest.id, reason = "Tapping ${nextBest.label}")
        }

        // 5. Scrolling: If nothing found on this screen, try to scroll
        if (elements.any { it.scrollable } && history.count { it.action.type == ActionType.SCROLL } < 2) {
            return Action(ActionType.SCROLL, direction = Direction.DOWN, reason = "Searching further down")
        }

        // 6. Success Check: Only finish if we've actually done something meaningful
        val typedCount = history.count { it.action.type == ActionType.TYPE && it.succeeded }
        val tappedCount = history.count { it.action.type == ActionType.TAP && it.succeeded }
        
        // Don't stop immediately after opening a complex URI; we need to verify the app is open and interactable
        val isInTargetApp = currentPackage.contains("calendar") || 
                           currentPackage.contains("gm") || 
                           currentPackage.contains("email") ||
                           currentPackage.contains("messaging") ||
                           currentPackage.contains("tasks")

        if (isInTargetApp) {
            // In these apps, we usually expect to have typed something AND tapped a save/send button
            if (typedCount > 0 && tappedCount > 0) {
                return Action(ActionType.WAIT, done = true, reason = "Goal achieved successfully")
            } else if (history.size > 15) {
                return Action(ActionType.WAIT, done = true, reason = "Stopped: step limit reached")
            }
        } else {
            // For other tasks, finish if we've done any action
            if (typedCount > 0 || tappedCount > 0) {
                return Action(ActionType.WAIT, done = true, reason = "Task finished")
            } else if (history.size > 10) {
                return Action(ActionType.WAIT, done = true, reason = "Stopped: no actions performed")
            }
        }

        return Action(ActionType.WAIT, reason = "Waiting for screen update...")
    }

    /** Overlap between goal words and this element's label. */
    private fun score(element: UiElement, goalTokens: Set<String>): Int {
        val label = (element.label ?: return 0).lowercase()
        val labelTokens = tokenize(label)
        var score = labelTokens.count { it in goalTokens }

        // Synonym bonus: "create" matches "add", "send" matches "email", etc.
        if (score == 0) {
            if ("create" in label && ("add" in goalTokens || "new" in goalTokens)) score += 2
            if ("save" in label && ("add" in goalTokens || "event" in goalTokens || "done" in goalTokens)) score += 2
            if ("send" in label && ("email" in goalTokens || "message" in goalTokens || "gmail" in goalTokens)) score += 2
            if ("compose" in label && ("email" in goalTokens || "message" in goalTokens)) score += 1
            if ("plus" in label || "add" in label) if ("new" in goalTokens || "add" in goalTokens) score += 2
            if ("done" in label || "check" in label) score += 1
        }

        return score
    }

    /**
     * Pulls a literal value out of the goal for a given field.
     *
     * Handles: email addresses, quoted text, subjects, and basic date parsing.
     */
    private fun extractValue(goal: String, field: UiElement): String? {
        val hint = (field.label ?: "").lowercase()
        val goalLower = goal.lowercase()

        // 1. Recipient / To
        if ("email" in hint || "to" == hint.trim() || "recipient" in hint) {
            EMAIL.find(goal)?.let { return it.value }
        }

        // 2. Subject
        if ("subject" in hint) {
            return goalLower.substringAfter("subject", "").trim().trim('"', ':', ' ')
                .takeIf { it.isNotBlank() }?.let { if (it.length > 50) it.take(47) + "..." else it }
        }

        // 3. Body / Message
        if ("message" in hint || "body" in hint || "compose email" in hint || "text" in hint) {
            val quoted = QUOTED.find(goal)
            if (quoted != null) return quoted.groupValues[1]
            
            // Try extracting after "saying" or "message"
            val afterSaying = goalLower.substringAfter("saying", "").trim()
            if (afterSaying.isNotBlank()) return afterSaying
            
            val afterMessage = goalLower.substringAfter("message", "").trim()
            if (afterMessage.isNotBlank()) return afterMessage
        }

        // 4. Date / Time (Standardize phonetic variations)
        if ("date" in hint || "when" in hint || "time" in hint || "start" in hint) {
            // Handle "tomorrow", "today", "next [day]"
            val now = java.util.Calendar.getInstance()
            if ("tomorrow" in goalLower) {
                now.add(java.util.Calendar.DAY_OF_YEAR, 1)
            } else if ("today" in goalLower) {
                // Keep today
            } else {
                // Look for days of week
                val days = mapOf("monday" to 2, "tuesday" to 3, "wednesday" to 4, "thursday" to 5, "friday" to 6, "saturday" to 7, "sunday" to 1)
                val targetDay = days.keys.firstOrNull { it in goalLower }
                if (targetDay != null) {
                    val targetValue = days[targetDay]!!
                    var diff = targetValue - now.get(java.util.Calendar.DAY_OF_WEEK)
                    if (diff <= 0) diff += 7
                    now.add(java.util.Calendar.DAY_OF_YEAR, diff)
                }
            }
            
            // Standard "Month DD YYYY"
            val monthMatch = Regex("(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,)?\\s+(\\d{4})").find(goalLower)
            if (monthMatch != null) {
                val month = when(monthMatch.groupValues[1]) {
                    "january" -> "01"; "february" -> "02"; "march" -> "03"; "april" -> "04"
                    "may" -> "05"; "june" -> "06"; "july" -> "07"; "august" -> "08"
                    "september" -> "09"; "october" -> "10"; "november" -> "11"; "december" -> "12"
                    else -> "01"
                }
                val day = monthMatch.groupValues[2].padStart(2, '0')
                val year = monthMatch.groupValues[3]
                return "$year-$month-$day"
            }
            
            return String.format(java.util.Locale.US, "%04d-%02d-%02d", now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
        }

        // 5. Search / Query
        if ("search" in hint || "query" in hint || "type web address" in hint) {
            return goal.substringAfter("for", "").trim().trim('"', ':', ' ')
                .takeIf { it.isNotBlank() } ?: QUOTED.find(goal)?.groupValues?.get(1)
        }

        // 6. Title / Event name / Generic Title extraction
        if ("title" in hint || "event" in hint || "name" in hint || "summary" in hint || "subject" in hint) {
            val quoted = QUOTED.find(goal)
            if (quoted != null) return quoted.groupValues[1]

            val afterCalled = goalLower.substringAfter("called", "").trim()
            if (afterCalled.isNotBlank()) return afterCalled.substringBefore("\n").trim('"', ' ')

            val afterTitled = goalLower.substringAfter("titled", "").trim()
            if (afterTitled.isNotBlank()) return afterTitled.substringBefore("\n").trim('"', ' ')
            
            val afterAbout = goalLower.substringAfter("about", "").trim()
            if (afterAbout.isNotBlank()) return afterAbout.substringBefore("\n").trim('"', ' ')
        }

        // 7. Generic catch-all: if it's the first empty editable field and we have quoted text, use it.
        QUOTED.find(goal)?.let { return it.groupValues[1] }
        
        return null
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9@.]+"))
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
            .toSet()

    private companion object {
        val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
        val QUOTED = Regex("[\"“']([^\"”']{2,})[\"”']")
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "then", "please", "can", "you", "will",
            "that", "this", "from", "into", "app",
        )
    }
}
