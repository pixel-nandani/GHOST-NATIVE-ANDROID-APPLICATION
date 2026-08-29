package com.ghost.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.provider.CalendarContract
import android.content.Intent
import android.net.Uri
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ghost.agent.core.agent.ActionOutcome
import com.ghost.agent.core.agent.DeviceController
import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.Bounds
import com.ghost.agent.core.model.Direction
import com.ghost.agent.core.model.ScreenSnapshot
import com.ghost.agent.core.model.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The real [DeviceController]: perception layer (Section 3.1) and grounding layer
 * (Section 3.3) of the design doc.
 *
 * This is the *only* class that touches [AccessibilityNodeInfo]. Everything upstream
 * of it works on the plain [UiElement] value type, which is what makes the agent loop
 * JVM-testable.
 *
 * **The id -> node map is the grounding mechanism.** [snapshot] numbers each element
 * and keeps a parallel map to the live node; [perform] resolves the model's
 * `target_id` through that map. The map is rebuilt from scratch on every snapshot, so
 * a stale id from a previous turn resolves to nothing rather than to the wrong button.
 */
class AccessibilityDeviceController(
    private val service: AccessibilityService,
) : DeviceController {

    /** Live nodes for the most recent snapshot only. Cleared on every capture. */
    private val nodesById = mutableMapOf<Int, AccessibilityNodeInfo>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- perception

    override suspend fun snapshot(): ScreenSnapshot? {
        val root = service.rootInActiveWindow ?: return null

        nodesById.clear()
        val elements = mutableListOf<UiElement>()
        var nextId = 1

        // Iterative BFS rather than recursion: some apps (long chat lists, nested
        // scroll containers) produce trees deep enough to make a recursive walk
        // uncomfortably close to a StackOverflowError.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue += root to 0
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES_VISITED) {
            val (node, depth) = queue.removeFirst()
            visited++

            if (depth <= MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue += it to depth + 1 }
                }
            }

            if (!node.isVisibleToUser) continue

            val element = toElement(node, nextId) ?: continue
            if (!element.isInteresting) continue

            elements += element
            nodesById[nextId] = node
            nextId++
        }

        if (visited >= MAX_NODES_VISITED) {
            Log.w(TAG, "node walk hit the $MAX_NODES_VISITED cap; tree may be truncated")
        }

        return ScreenSnapshot(
            packageName = root.packageName?.toString() ?: "",
            elements = elements,
            windowTitle = root.text?.toString(),
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    private fun toElement(node: AccessibilityNodeInfo, id: Int): UiElement? {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return null

        return UiElement(
            id = id,
            className = node.className?.toString() ?: "View",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            // A node is treated as clickable if it is clickable itself OR sits inside a
            // clickable parent -- Compose and RecyclerView rows routinely put the text
            // in a non-clickable child of the real click target, and matching only
            // `isClickable` makes half of a normal list look untappable to the planner.
            clickable = node.isClickable || hasClickableAncestor(node),
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            focused = node.isFocused,
        )
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < CLICKABLE_ANCESTOR_HOPS) {
            if (parent.isClickable) return true
            parent = parent.parent
            hops++
        }
        return false
    }

    // ------------------------------------------------------------------ grounding

    override suspend fun perform(action: Action): ActionOutcome {
        return try {
            when (action.type) {
                ActionType.TAP -> tap(action)
                ActionType.TYPE -> type(action)
                ActionType.SCROLL -> scroll(action)
                ActionType.SWIPE -> swipe(action.direction ?: Direction.UP)
                ActionType.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back")
                ActionType.WAIT -> ActionOutcome.Success
                ActionType.OPEN_APP -> openApp(action.value)
            }
        } catch (e: Exception) {
            // The loop counts failures and aborts on a streak. Throwing here would
            // instead kill the accessibility service and require re-toggling the
            // permission by hand -- unacceptable mid-demo.
            Log.e(TAG, "action ${action.type} failed", e)
            ActionOutcome.failure(e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private suspend fun tap(action: Action): ActionOutcome {
        val id = action.targetId ?: return ActionOutcome.failure("tap without target_id")
        val node = nodesById[id] ?: return ActionOutcome.failure("element $id is gone from the screen")

        // performAction is preferred over a synthetic gesture when the node accepts it:
        // it goes through the app's own click handling, so it works even if the element
        // is partially covered by the keyboard or the status bubble.
        val clickTarget = node.takeIf { it.isClickable } ?: clickableAncestorOf(node)
        if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionOutcome.Success
        }

        // Fall back to a real gesture at the node's centre.
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) {
            return ActionOutcome.failure("element $id has zero bounds")
        }
        val ok = dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
        return if (ok) ActionOutcome.Success else ActionOutcome.failure("gesture was not dispatched")
    }

    private fun clickableAncestorOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < CLICKABLE_ANCESTOR_HOPS) {
            if (parent.isClickable) return parent
            parent = parent.parent
            hops++
        }
        return null
    }

    private suspend fun type(action: Action): ActionOutcome {
        val id = action.targetId ?: return ActionOutcome.failure("type without target_id")
        val value = action.value ?: return ActionOutcome.failure("type without value")
        val node = nodesById[id] ?: return ActionOutcome.failure("element $id is gone from the screen")

        if (!node.isEditable) return ActionOutcome.failure("element $id is not editable")

        // 1. Focus the field
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(100L) // Wait for focus to land

        // 2. Try ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionOutcome.Success
        }

        // 3. Fallback: Try putting it on the clipboard and pasting
        return try {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ghost_type", value))
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                ActionOutcome.Success
            } else {
                ActionOutcome.failure("could not set text or paste")
            }
        } catch (e: Exception) {
            ActionOutcome.failure("type failed: ${e.message}")
        }
    }

    private suspend fun scroll(action: Action): ActionOutcome {
        // Prefer the node's own scroll action -- it respects the app's fling physics and
        // lands on item boundaries, where a synthetic swipe often stops mid-row.
        val target = action.targetId?.let { nodesById[it] }
            ?: nodesById.values.firstOrNull { it.isScrollable }

        val nodeAction = if (action.direction == Direction.UP) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        if (target != null && target.isScrollable && target.performAction(nodeAction)) {
            return ActionOutcome.Success
        }
        return swipe(if (action.direction == Direction.UP) Direction.DOWN else Direction.UP)
    }

    /** [direction] is the finger's travel direction. Swiping UP scrolls content down. */
    private suspend fun swipe(direction: Direction): ActionOutcome {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        val (from, to) = when (direction) {
            Direction.UP -> (cx to h * 0.70f) to (cx to h * 0.30f)
            Direction.DOWN -> (cx to h * 0.30f) to (cx to h * 0.70f)
            Direction.LEFT -> (w * 0.80f to cy) to (w * 0.20f to cy)
            Direction.RIGHT -> (w * 0.20f to cy) to (w * 0.80f to cy)
        }

        val path = Path().apply {
            moveTo(from.first, from.second)
            lineTo(to.first, to.second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
        return if (ok) ActionOutcome.Success else ActionOutcome.failure("swipe was not dispatched")
    }

    private fun global(actionId: Int, label: String): ActionOutcome =
        if (service.performGlobalAction(actionId)) ActionOutcome.Success
        else ActionOutcome.failure("global action $label was refused")

    private fun openApp(value: String?): ActionOutcome {
        if (value.isNullOrBlank()) return ActionOutcome.failure("open_app without target")

        return try {
            val uri = Uri.parse(value)
            when {
                // 1. Native Email Intent (Pre-filled draft)
                value.startsWith("mailto:") -> {
                    val to = uri.schemeSpecificPart.substringBefore('?')
                    val body = uri.getQueryParameter("body") ?: ""
                    val subject = uri.getQueryParameter("subject") ?: "Automated Task"
                    
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$to")
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    
                    // Force Gmail or similar if available
                    val gmail = "com.google.android.gm"
                    if (isAppInstalled(gmail)) intent.setPackage(gmail)
                    
                    service.startActivity(intent)
                    ActionOutcome.Success
                }

                // 2. Native Calendar Intent (Explicit Payload)
                value.startsWith("ghost://calendar/create") -> {
                    val title = uri.getQueryParameter("title") ?: "New Event"
                    val date = uri.getQueryParameter("date") // YYYY-MM-DD
                    
                    Log.i(TAG, "Executing native calendar insertion for: $title on $date")
                    
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, "Created by Ghost Agent")
                        
                        val cal = java.util.Calendar.getInstance()
                        date?.let {
                            runCatching {
                                val parts = it.split("-")
                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 10, 0)
                            }.onFailure { Log.e(TAG, "Failed to parse date: $it") }
                        }
                        
                        val startTime = cal.timeInMillis
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startTime + 3600000)
                        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
                        
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                    ActionOutcome.Success
                }

                // 3. General URI / Deep Link
                value.contains("://") -> {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                    ActionOutcome.Success
                }

                // 4. Standard Package Name
                else -> {
                    val intent = service.packageManager.getLaunchIntentForPackage(value)
                        ?: return ActionOutcome.failure("$value is not installed")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    service.startActivity(intent)
                    ActionOutcome.Success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to execute intent: $value", e)
            ActionOutcome.failure("system error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------- gestures

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Suspends until the OS reports the gesture finished.
     *
     * Waiting for the callback rather than trusting `dispatchGesture`'s return value
     * matters: the return value only says the gesture was *accepted*, so firing the
     * next perception pass off it reads the screen while the tap is still animating.
     */
    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = service.dispatchGesture(gesture, callback, mainHandler)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ---------------------------------------------------------------------- settle

    /**
     * Post-action pause before the next perception pass.
     *
     * Per-action rather than one flat delay: taps usually start a transition and need
     * real time, whereas typing lands synchronously. A single conservative delay would
     * add seconds to a 15-step demo for no benefit.
     *
     * A proper implementation would await `TYPE_WINDOW_CONTENT_CHANGED` with this as the
     * ceiling; these numbers are the honest, tuned-on-device version of that. Re-tune
     * them on the loaner phone -- they are the main lever on demo pacing.
     */
    override suspend fun settle(afterAction: Action) {
        val ms = when (afterAction.type) {
            ActionType.TAP -> 700L        // may launch a screen transition
            ActionType.OPEN_APP -> 1500L  // cold app start
            ActionType.BACK -> 500L
            ActionType.SCROLL, ActionType.SWIPE -> 400L
            ActionType.TYPE -> 200L       // lands synchronously
            ActionType.WAIT -> 1000L
        }
        delay(ms)
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            service.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "GhostPerception"

        /**
         * Node-visit ceiling per snapshot. A Gmail inbox exposes ~300 nodes; walking
         * tens of thousands in a pathological tree would blow the per-step latency
         * budget that the whole pitch rests on.
         */
        private const val MAX_NODES_VISITED = 1200
        private const val MAX_DEPTH = 40
        private const val CLICKABLE_ANCESTOR_HOPS = 3
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 300L
    }
}
