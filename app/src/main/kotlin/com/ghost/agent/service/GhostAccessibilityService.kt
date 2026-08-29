package com.ghost.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.ghost.agent.core.agent.AgentLoop
import com.ghost.agent.core.planning.HeuristicPlanner
import com.ghost.agent.core.planning.LlmPlanner
import com.ghost.agent.core.planning.Planner
import com.ghost.agent.core.safety.SafetyGate
import com.ghost.agent.core.safety.SafetyPolicy
import com.ghost.agent.llm.MediaPipeLlmEngine
import com.ghost.agent.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns the agent loop for the lifetime of the enabled accessibility service.
 *
 * This class is intentionally thin -- wiring, lifecycle, and nothing else. All the
 * behaviour lives in the pure core (`core.*`), which is why none of the interesting
 * logic requires a device to test.
 *
 * The service is a persistent bound system service, so no separate foreground service
 * is declared: the OS already keeps this process alive while the toggle is on. (This is
 * a deliberate deviation from doc Section 6, which listed FOREGROUND_SERVICE as
 * recommended -- adding one here would be dead code.)
 */
class GhostAccessibilityService : AccessibilityService(), GhostEngine {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The current task's coroutine. Cancelling it is the kill switch. */
    private var taskJob: Job? = null

    private var powerButtonDownTime: Long = 0
    private val LONG_PRESS_THRESHOLD = 3000L // 3 seconds
    private val powerButtonHandler = Handler(Looper.getMainLooper())
    private val triggerRunnable = Runnable {
        Log.i(TAG, "Power button long-press (3s) detected. Starting automation.")
        val launchIntent = Intent(this, com.ghost.agent.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(launchIntent)
    }

    private val powerButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Screen events are no longer used for 3s long press
        }
    }

    private lateinit var device: AccessibilityDeviceController
    private lateinit var overlay: OverlayController
    private var planner: Planner = HeuristicPlanner()

    override val plannerName: String get() = planner.name

    override var hasModel: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(powerButtonReceiver, filter)

        device = AccessibilityDeviceController(this)
        overlay = OverlayController(this)
        planner = buildPlanner()

        GhostSession.attach(this)

        // The overlay mirrors GhostSession state rather than being poked imperatively
        // from the loop -- one source of truth, so the bubble can never show a step the
        // loop has already moved past.
        GhostSession.state
            .onEach { overlay.render(it) }
            .launchIn(serviceScope)
    }

    /**
     * Real weights if they are on device, heuristic fallback otherwise.
     *
     * The fallback is never silent: [hasModel] drives a banner in the UI and the
     * overlay reports the backend as `heuristic`.
     */
    private fun buildPlanner(): Planner {
        val engine = MediaPipeLlmEngine.tryCreate(this)
        return if (engine != null) {
            hasModel = true
            LlmPlanner(engine)
        } else {
            hasModel = false
            HeuristicPlanner().also { Log.w(TAG, "no on-device model; using ${it.name}") }
        }
    }

    override fun startTask(goal: String) {
        if (taskJob?.isActive == true) {
            Log.w(TAG, "task already running; ignoring start request")
            return
        }

        val loop = AgentLoop(
            device = device,
            planner = planner,
            gate = SafetyGate(SafetyPolicy.DEMO),
            confirmer = GhostSession,
            onEvent = GhostSession::publish,
        )

        taskJob = serviceScope.launch {
            try {
                val outcome = loop.run(goal)
                // The pitch's metrics numbers, measured rather than estimated.
                Log.i(TAG, "task finished: ${outcome.summary} | ${loop.timings.summary()}")
                Log.i(TAG, "timings csv:\n${loop.timings.toCsv()}")
            } catch (e: Exception) {
                Log.e(TAG, "task crashed", e)
            }
        }
    }

    override fun stopTask() {
        taskJob?.cancel()
        taskJob = null
    }

    /**
     * Ghost is a *pull*-based agent: it reads the tree when the loop asks for it, not
     * when the OS notifies. Events are ignored on purpose -- reacting to every
     * TYPE_WINDOW_CONTENT_CHANGED would fire perception dozens of times per second
     * during a scroll and starve the planning step.
     *
     * The event types declared in accessibility_service_config.xml exist only to keep
     * the service bound and eligible to retrieve window content.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopTask()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (powerButtonDownTime == 0L) {
                    powerButtonDownTime = System.currentTimeMillis()
                    powerButtonHandler.postDelayed(triggerRunnable, LONG_PRESS_THRESHOLD)
                }
                // Don't return true: let the system handle the first press for safety
            } else if (event.action == KeyEvent.ACTION_UP) {
                powerButtonHandler.removeCallbacks(triggerRunnable)
                powerButtonDownTime = 0L
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "unbinding")
        stopTask()
        overlay.hide()
        GhostSession.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(powerButtonReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        serviceScope.cancel()
        planner.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "GhostService"
    }
}
