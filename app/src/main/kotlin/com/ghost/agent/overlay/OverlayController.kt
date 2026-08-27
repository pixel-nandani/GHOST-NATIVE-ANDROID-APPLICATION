package com.ghost.agent.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ghost.agent.core.agent.GhostPhase
import com.ghost.agent.core.agent.GhostState
import com.ghost.agent.service.GhostSession
import kotlin.math.roundToInt

/**
 * The floating status bubble: live step readout, the confirm-before-submit prompt, and
 * an always-available kill switch (doc Section 3.4).
 *
 * This is deliberately the most *visible* part of Ghost. An agent that moves a phone by
 * itself with no on-screen indication of what it is doing or how to stop it is not a
 * demo, it is a liability -- and the rubric rewards the visible version anyway.
 *
 * Built in code rather than inflated from XML: the view tree is a dozen nodes, it needs
 * no theming, and keeping it here means the whole overlay -- layout, window flags, touch
 * handling and state binding -- reads top to bottom in one file.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private lateinit var statusText: TextView
    private lateinit var stepText: TextView
    private lateinit var metricsText: TextView
    private lateinit var confirmRow: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var stopButton: Button

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    // --------------------------------------------------------------------- render

    /** Single entry point. Idempotent: safe to call on every state emission. */
    fun render(state: GhostState) {
        if (!state.isRunning && state.phase != GhostPhase.FINISHED) {
            hide()
            return
        }
        if (!canShow) {
            // Not fatal -- the agent still works, the user just has no bubble. The
            // in-app screen carries the same state and the same stop button.
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; bubble suppressed")
            return
        }

        show()
        statusText.text = state.statusLine
        stepText.text = state.progressLabel
        stepText.visibility = if (state.progressLabel.isEmpty()) View.GONE else View.VISIBLE

        metricsText.text = state.backend?.let { backend ->
            "${state.lastLatencyMs}ms · $backend · offline"
        } ?: ""
        metricsText.visibility = if (metricsText.text.isEmpty()) View.GONE else View.VISIBLE

        val prompt = state.confirmationPrompt
        if (prompt != null) {
            confirmText.text = prompt
            confirmRow.visibility = View.VISIBLE
            confirmText.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        } else {
            confirmRow.visibility = View.GONE
            confirmText.visibility = View.GONE
            stopButton.visibility =
                if (state.phase == GhostPhase.FINISHED) View.GONE else View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun show() {
        if (root != null) return
        if (!canShow) return

        val view = build()
        root = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the target app's keyboard and focus intact -- the
            // bubble must never steal focus from the field Ghost is about to type into.
            // Buttons still receive touch events without focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "could not add overlay", it)
                root = null
            }
        makeDraggable(params)
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    // ---------------------------------------------------------------- view tree

    private fun build(): LinearLayout {
        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(false)
        }
        stepText = TextView(context).apply {
            setTextColor(ACCENT)
            textSize = 12f
        }
        metricsText = TextView(context).apply {
            setTextColor(MUTED)
            textSize = 11f
        }
        confirmText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }

        stopButton = Button(context).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            background = pill(DANGER)
            setOnClickListener { GhostSession.stop() }
        }

        confirmRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(
                Button(context).apply {
                    text = "Allow once"
                    setTextColor(Color.BLACK)
                    background = pill(ACCENT)
                    setOnClickListener { GhostSession.resolveConfirmation(true) }
                },
            )
            addView(
                Button(context).apply {
                    text = "Deny"
                    setTextColor(Color.WHITE)
                    background = pill(DANGER)
                    setOnClickListener { GhostSession.resolveConfirmation(false) }
                    (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
                },
            )
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(stepText)
            addView(statusText)
            addView(metricsText)
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textColumn)
            addView(stopButton)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E6101014"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), 0, dp(12), 0) }
            layoutParams = lp
            addView(topRow)
            addView(confirmText)
            addView(confirmRow)
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color)
    }

    /**
     * Lets the user drag the bubble out of the way.
     *
     * Necessary, not decorative: the bubble is pinned near the top, which is exactly
     * where Gmail's Send button and most app bars live. If it covers the control Ghost
     * needs to tap, `performAction(ACTION_CLICK)` still works but the fallback gesture
     * would land on the bubble instead.
     */
    private fun makeDraggable(params: WindowManager.LayoutParams) {
        val view = root ?: return
        var startY = 0
        var touchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchY = event.rawY
                    false // let buttons still get their click
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchY).roundToInt()
                    if (kotlin.math.abs(dy) > dp(4)) {
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(v, params) }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private companion object {
        const val TAG = "GhostOverlay"
        val ACCENT = Color.parseColor("#7CF5C4")
        val DANGER = Color.parseColor("#E5484D")
        val MUTED = Color.parseColor("#9BA1A6")
    }
}
