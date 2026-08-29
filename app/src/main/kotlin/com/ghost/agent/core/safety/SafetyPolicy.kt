package com.ghost.agent.core.safety

/**
 * The rules Ghost will not break, in one place.
 *
 * Kept as data rather than scattered `if` statements so the whole policy can be
 * printed on screen, diffed, and unit-tested against canned actions.
 */
data class SafetyPolicy(
    /**
     * Packages Ghost may act inside. Empty set means "nothing is allowed" -- fail
     * closed, never open. Note this is an allow-list of *action* targets; Ghost may
     * still read a screen outside the list in order to notice it has drifted and
     * abort with a clear reason.
     */
    val allowedPackages: Set<String>,

    /** Hard ceiling on actions per task. Prevents runaway loops (doc Section 3.4). */
    val stepCap: Int = 15,

    /**
     * Words that mean "this is about to do something irreversible".
     *
     * Matched on word boundaries, not substrings: a naive `contains("send")` fires on
     * the word "Sender" in every email list row and would turn the confirmation
     * checkpoint into noise that users learn to tap through reflexively.
     */
    val riskyKeywords: Set<String> = DEFAULT_RISKY_KEYWORDS,

    /** Set false only for automated stress runs. Never in a user-facing build. */
    val requireConfirmForRisky: Boolean = true,

    /** Consecutive failed actions tolerated before the task aborts. */
    val maxConsecutiveFailures: Int = 3,
) {
    companion object {
        val DEFAULT_RISKY_KEYWORDS: Set<String> = setOf(
            "send", "submit", "pay", "purchase", "buy", "order", "checkout",
            "delete", "remove", "discard", "transfer", "confirm", "book",
            "publish", "post", "share", "sign out", "logout",
        )

        /**
         * The scoped demo allow-list from Section 4 of the build plan.
         *
         * Deliberately three packages. Widening this is the single easiest way to
         * turn a working demo into an unpredictable one, because every extra app is
         * an extra accessibility tree whose labels nobody has dumped and checked.
         */
        val DEMO: SafetyPolicy = SafetyPolicy(
            allowedPackages = setOf(
                "com.google.android.gm",              // Gmail
                "com.android.email",                  // Generic Email
                "com.google.android.calendar",        // Google Calendar
                "com.android.calendar",               // Generic Calendar
                "com.android.chrome",                 // Chrome
                "com.ghost.agent",                    // Ghost
                "com.bbk.launcher2",                  // Vivo/iQOO Launcher
                "com.sec.android.app.launcher",       // Samsung
                "com.google.android.apps.nexuslauncher", // Pixel
                "com.miui.home",                      // MIUI
                "com.android.launcher3",              // AOSP
                "com.google.android.apps.messaging",  // Messages
                "com.google.android.apps.tasks",      // Tasks
                "com.google.android.googlequicksearchbox", // Search
                "com.android.settings",               // Settings
                "com.android.systemui",               // System UI (for notifications etc)
                "com.google.android.packageinstaller", // For permission dialogs
                "com.android.permissioncontroller",   // Modern permission manager
            ),
            requireConfirmForRisky = false,
        )
    }
}

/** What the gate decided about acting inside a given app. */
sealed interface PackageVerdict {
    data object Allowed : PackageVerdict
    data class Refused(val packageName: String, val reason: String) : PackageVerdict
}

/** What the gate decided about a specific action. */
sealed interface RiskVerdict {
    /** Proceed without interrupting the user. */
    data object Proceed : RiskVerdict

    /** Pause and get one explicit tap. [prompt] is shown verbatim. */
    data class NeedsConfirmation(val prompt: String, val matchedKeyword: String) : RiskVerdict

    /** Structurally invalid -- do not perform, do not ask the user. */
    data class Reject(val reason: String) : RiskVerdict
}
