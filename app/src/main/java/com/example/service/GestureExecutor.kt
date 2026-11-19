package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.AgentActionType
import com.example.agent.PlannedAction
import com.example.agent.ScreenNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "GestureExecutor"

object GestureExecutor {

    data class ExecutionResult(
        val success: Boolean,
        val summary: String
    )

    suspend fun execute(
        service: AccessibilityService,
        action: PlannedAction,
        nodes: List<ScreenNode>,
        screenWidth: Int,
        screenHeight: Int
    ): ExecutionResult {
        val targetNode = action.targetNodeId?.let { id -> nodes.firstOrNull { it.id == id } }

        return when (action.actionType) {
            AgentActionType.TAP -> {
                val tapX = (action.x ?: targetNode?.bounds?.centerX)?.coerceIn(1, screenWidth - 1)
                val tapY = (action.y ?: targetNode?.bounds?.centerY)?.coerceIn(1, screenHeight - 1)

                if (tapX == null || tapY == null) {
                    return ExecutionResult(false, "No valid coordinates or node bounds for TAP.")
                }

                val gestureOk = dispatchTap(service, tapX.toFloat(), tapY.toFloat())
                if (gestureOk) {
                    ExecutionResult(true, "Tapped ($tapX, $tapY) ${targetNode?.displayLabel()?.let { "[$it]" } ?: ""}".trim())
                } else if (targetNode != null) {
                    val root = service.rootInActiveWindow
                    var liveNode = NodeTreeParser.findAccessibilityNodeByBounds(
                        root,
                        targetNode.bounds,
                        screenWidth,
                        screenHeight
                    )
                    var clicked = false
                    var depth = 0
                    while (liveNode != null && depth < 4) {
                        if (liveNode.isClickable && liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            clicked = true
                            break
                        }
                        liveNode = liveNode.parent
                        depth++
                    }
                    ExecutionResult(clicked, if (clicked) "Clicked node #${targetNode.id}" else "Tap failed at ($tapX, $tapY)")
                } else {
                    ExecutionResult(false, "Gesture dispatch rejected at ($tapX, $tapY)")
                }
            }

            AgentActionType.DRAG -> {
                val startX = (action.x ?: targetNode?.bounds?.centerX)?.coerceIn(1, screenWidth - 1)
                val startY = (action.y ?: targetNode?.bounds?.centerY)?.coerceIn(1, screenHeight - 1)
                val endX = action.endX?.coerceIn(1, screenWidth - 1)
                val endY = action.endY?.coerceIn(1, screenHeight - 1)

                if (startX == null || startY == null || endX == null || endY == null) {
                    return ExecutionResult(false, "DRAG requires start (x,y) and target (endX,endY) coordinates.")
                }
                val duration = (action.durationMs ?: 450L).coerceIn(120L, 2500L)
                val ok = dispatchSwipe(
                    service = service,
                    startX = startX.toFloat(),
                    startY = startY.toFloat(),
                    endX = endX.toFloat(),
                    endY = endY.toFloat(),
                    durationMs = duration
                )
                ExecutionResult(
                    ok,
                    if (ok) "Dragged ($startX,$startY) -> ($endX,$endY) [${duration}ms]"
                    else "Drag rejected ($startX,$startY) -> ($endX,$endY)"
                )
            }

            AgentActionType.LONG_PRESS -> {
                val tapX = (action.x ?: targetNode?.bounds?.centerX)?.coerceIn(1, screenWidth - 1)
                val tapY = (action.y ?: targetNode?.bounds?.centerY)?.coerceIn(1, screenHeight - 1)
                if (tapX == null || tapY == null) {
                    return ExecutionResult(false, "LONG_PRESS requires coordinates or node bounds.")
                }
                val duration = (action.durationMs ?: 700L).coerceIn(300L, 3000L)
                val ok = dispatchTap(service, tapX.toFloat(), tapY.toFloat(), durationMs = duration)
                ExecutionResult(
                    ok,
                    if (ok) "Long-pressed ($tapX, $tapY) [${duration}ms]"
                    else "Long-press rejected at ($tapX, $tapY)"
                )
            }

            AgentActionType.INPUT_TEXT -> {
                val text = action.textToType.orEmpty()
                if (text.isBlank()) {
                    return ExecutionResult(false, "INPUT_TEXT requested with empty text.")
                }
                val root = service.rootInActiveWindow
                var liveNode = targetNode?.let {
                    NodeTreeParser.findAccessibilityNodeByBounds(root, it.bounds, screenWidth, screenHeight)
                }
                if (liveNode == null) {
                    liveNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
                val focusTapX = action.x ?: targetNode?.bounds?.centerX
                val focusTapY = action.y ?: targetNode?.bounds?.centerY
                if ((liveNode == null || !liveNode.isFocused) && focusTapX != null && focusTapY != null) {
                    dispatchTap(
                        service,
                        focusTapX.coerceIn(1, screenWidth - 1).toFloat(),
                        focusTapY.coerceIn(1, screenHeight - 1).toFloat()
                    )
                    delay(260)
                    val refreshedRoot = service.rootInActiveWindow
                    liveNode = targetNode?.let {
                        NodeTreeParser.findAccessibilityNodeByBounds(refreshedRoot, it.bounds, screenWidth, screenHeight)
                    } ?: refreshedRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }

                if (liveNode != null) {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(120)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    var ok = liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (!ok) {
                        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("TapPilot", text))
                            delay(80)
                            ok = liveNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }
                    }
                    ExecutionResult(ok, if (ok) "Entered \"$text\"" else "Text entry rejected by field")
                } else {
                    ExecutionResult(false, "No editable node found to input text.")
                }
            }

            AgentActionType.SCROLL_DOWN -> {
                val cx = (screenWidth * 0.5f)
                val startY = (screenHeight * 0.72f)
                val endY = (screenHeight * 0.28f)
                val ok = dispatchSwipe(service, cx, startY, cx, endY)
                ExecutionResult(ok, if (ok) "Scrolled down" else "Scroll down failed")
            }

            AgentActionType.SCROLL_UP -> {
                val cx = (screenWidth * 0.5f)
                val startY = (screenHeight * 0.28f)
                val endY = (screenHeight * 0.72f)
                val ok = dispatchSwipe(service, cx, startY, cx, endY)
                ExecutionResult(ok, if (ok) "Scrolled up" else "Scroll up failed")
            }

            AgentActionType.BACK -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ExecutionResult(ok, "Pressed Back")
            }

            AgentActionType.HOME -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ExecutionResult(ok, "Navigated Home")
            }

            AgentActionType.WAIT -> {
                delay(1500)
                ExecutionResult(true, "Waited for screen update")
            }

            AgentActionType.COMPLETE -> ExecutionResult(true, action.reason)
            AgentActionType.FAIL -> ExecutionResult(false, action.reason)
        }
    }

    private suspend fun dispatchTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 65L
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(service, gesture)
    }

    private suspend fun dispatchSwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 380L
    ): Boolean {
        if (durationMs >= 480L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Brief 75ms touch-down hold lets game engines (Unity/Cocos2d-x) lock onto the striker before pulling back
            val holdPath = Path().apply { moveTo(startX, startY) }
            val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, 75L, true)
            val holdGesture = GestureDescription.Builder().addStroke(holdStroke).build()
            if (!dispatchAndAwait(service, holdGesture)) {
                return false
            }

            val pullPath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val pullStroke = holdStroke.continueStroke(pullPath, 0L, durationMs, false)
            val pullGesture = GestureDescription.Builder().addStroke(pullStroke).build()
            return dispatchAndAwait(service, pullGesture)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(service, gesture)
    }

    private suspend fun dispatchAndAwait(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean {
        return withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled by system")
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }
}
