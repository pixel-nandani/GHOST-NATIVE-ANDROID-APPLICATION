package com.ghost.agent.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import com.ghost.agent.service.GhostAccessibilityService

/**
 * Setup-state checks and the intents that fix them.
 *
 * Both permissions Ghost needs are OS-level toggles that cannot be granted from a
 * runtime dialog, so the app's only move is to detect the state accurately and hand the
 * user a one-tap route to the right settings screen.
 */
object SetupChecks {

    /**
     * Whether Ghost's accessibility service is enabled.
     *
     * Reads the secure setting directly instead of using
     * `AccessibilityManager.getEnabledAccessibilityServiceList`, because that API
     * reports services matching a feedback type and has been unreliable across OEM
     * skins -- and being wrong here means telling the user everything is fine while the
     * agent cannot read a single screen.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, GhostAccessibilityService::class.java)
            .flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (entry in splitter) {
            // Compare loosely: some skins store the short form, some the flattened form.
            if (entry.equals(expected, ignoreCase = true)) return true
            if (entry.startsWith(context.packageName, ignoreCase = true)) return true
        }
        return false
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Opens Accessibility settings.
     *
     * There is no reliable way to deep-link to a *specific* service's toggle across
     * OEMs, so this lands on the list and the UI tells the user what to look for. Doc
     * Section 10 flags this as demo-day friction -- do this once before the pitch, not
     * on stage.
     */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
