package com.ghost.agent

import com.ghost.agent.core.model.Bounds
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.model.UiElement

/**
 * Canned screens for tests.
 *
 * This is the code form of the "element cheat sheet" the build plan asks for in hours
 * 2-6: dump the real accessibility tree for each target screen, then encode it here.
 * Every flow you intend to demo should get a fixture, because a fixture turns "the demo
 * broke" into a failing test you can fix in two minutes instead of a re-run on the phone.
 *
 * Replace these with real dumps from the loaner device -- `adb shell uiautomator dump`,
 * or Ghost's own logcat output from a perception pass -- before relying on them.
 */
object Fixtures {

    const val PKG_PARKING = "com.android.chrome"
    const val PKG_GMAIL = "com.google.android.gm"

    fun element(
        id: Int,
        cls: String = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        bounds: Bounds = Bounds(40, 100 + id * 60, 340, 150 + id * 60),
    ) = UiElement(
        id = id,
        className = cls,
        text = text,
        contentDescription = desc,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
    )

    /** Appendix B of the design doc, verbatim. */
    fun parkingHome() = ScreenSnapshot(
        packageName = PKG_PARKING,
        elements = listOf(
            element(1, "android.widget.Button", text = "Renew Now", clickable = true),
            element(2, "android.widget.EditText", text = "Vehicle number", editable = true),
            element(3, text = "Expires: 12 Sept 2026"),
            element(4, "android.widget.Button", text = "Cancel", clickable = true),
        ),
    )

    /** A form with the risky commit button present -- exercises the safety gate. */
    fun parkingForm() = ScreenSnapshot(
        packageName = PKG_PARKING,
        elements = listOf(
            element(1, "android.widget.EditText", text = "MH31AB1234", editable = true),
            element(2, "android.widget.Button", text = "Submit payment", clickable = true),
            element(3, "android.widget.Button", text = "Back", clickable = true),
        ),
    )

    /** Gmail compose. Note "Sender" -- the word-boundary trap for naive keyword matching. */
    fun gmailCompose() = ScreenSnapshot(
        packageName = PKG_GMAIL,
        elements = listOf(
            element(1, "android.widget.EditText", desc = "To", editable = true),
            element(2, "android.widget.EditText", desc = "Subject", editable = true),
            element(3, "android.widget.EditText", desc = "Compose email", editable = true),
            element(4, "android.widget.TextView", text = "Sender: me@company.com"),
            element(5, "android.widget.Button", desc = "Send", clickable = true),
        ),
    )

    /** A canvas-rendered app: nothing in the tree. Must fail cleanly, not hang. */
    fun emptyScreen(pkg: String = PKG_PARKING) =
        ScreenSnapshot(packageName = pkg, elements = emptyList())
}
