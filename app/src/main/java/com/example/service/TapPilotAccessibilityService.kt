package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.example.agent.AgentCoordinator
import com.example.agent.AgentUiState
import com.example.agent.NotificationReplyHelper
import com.example.agent.NotificationSignal
import com.example.agent.PlannedAction
import com.example.agent.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TapPilotAccessibilityService : AccessibilityService() {

    private var serviceScope: CoroutineScope? = null
    private var overlayController: FloatingOverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentCoordinator.initialize(applicationContext)
        overlayController = FloatingOverlayController(this)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                combine(
                    AgentCoordinator.uiState,
                    AgentCoordinator.settings
                ) { state, settings ->
                    state to settings.showFloatingOverlay
                }.collectLatest { (state, showOverlay) ->
                    if (showOverlay) {
                        overlayController?.renderState(state)
                    } else {
                        overlayController?.renderState(AgentUiState.Idle)
                    }
                }
            }
        }
        AgentCoordinator.attachService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val sourcePkg = event.packageName?.toString().orEmpty()
            if (sourcePkg.isBlank() || sourcePkg == packageName) return

            val signal = extractNotificationSignal(event, sourcePkg)
            if (signal != null) {
                AgentCoordinator.onNotificationReceived(signal)
            }
        }
    }

    override fun onInterrupt() {
        AgentCoordinator.pauseTask("Interrupted by system accessibility service.")
    }

    override fun onDestroy() {
        AgentCoordinator.detachService(this)
        overlayController?.destroy()
        overlayController = null
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    suspend fun captureSnapshot(useScreenshot: Boolean): UiSnapshot {
        val (width, height) = getScreenDimensions()

        // Temporarily hide floating overlay so screenshots capture unobstructed target UI.
        val minFrameTs = SystemClock.uptimeMillis()
        overlayController?.setTemporarilyHidden(true)
        val settleMs = if (LiveMediaProjectionCapturer.isLiveActive.value) 50L else 80L
        delay(settleMs)
        val captureResult = try {
            ScreenCapturer.capture(this, useScreenshot, minFrameTs)
        } finally {
            overlayController?.setTemporarilyHidden(false)
        }

        val appWindowRoot = windows
            ?.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isActive &&
                    it.root?.packageName?.toString() != packageName
            }?.root
            ?: windows
                ?.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        it.root?.packageName?.toString() != packageName
                }?.root

        val activeRoot = rootInActiveWindow
        val root = if (activeRoot != null && activeRoot.packageName?.toString() != packageName) {
            activeRoot
        } else {
            appWindowRoot ?: activeRoot
        }

        val pkg = root?.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val nodes = NodeTreeParser.extractNodes(
            root = root,
            screenWidth = width,
            screenHeight = height,
            excludedPackage = packageName
        )

        return UiSnapshot(
            packageName = pkg,
            screenWidth = width,
            screenHeight = height,
            nodes = nodes,
            screenshot = captureResult.bitmap,
            captureNote = captureResult.note
        )
    }

    suspend fun executeAction(
        action: PlannedAction,
        snapshot: UiSnapshot
    ): GestureExecutor.ExecutionResult {
        overlayController?.setTouchPassthrough(true)
        return try {
            GestureExecutor.execute(
                service = this,
                action = action,
                nodes = snapshot.nodes,
                screenWidth = snapshot.screenWidth,
                screenHeight = snapshot.screenHeight
            )
        } finally {
            overlayController?.setTouchPassthrough(false)
        }
    }

    fun navigateHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun extractNotificationSignal(
        event: AccessibilityEvent,
        sourcePkg: String
    ): NotificationSignal? {
        val parcel = event.parcelableData
        val rawFallback = event.text?.joinToString(" ") { it?.toString().orEmpty() }?.trim().orEmpty()
        return if (parcel is Notification) {
            val isSummary = (parcel.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            val extras = parcel.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val bodyText = bigText?.takeIf { it.isNotBlank() }
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            NotificationReplyHelper.parseNotification(
                packageName = sourcePkg,
                title = title,
                body = bodyText,
                conversationTitle = conversationTitle,
                rawFallbackText = rawFallback,
                timestampMs = System.currentTimeMillis(),
                isGroupSummary = isSummary
            )
        } else {
            NotificationReplyHelper.parseNotification(
                packageName = sourcePkg,
                title = null,
                body = null,
                conversationTitle = null,
                rawFallbackText = rawFallback,
                timestampMs = System.currentTimeMillis(),
                isGroupSummary = false
            )
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
        }
    }

    companion object {
        fun isServiceEnabled(context: Context): Boolean {
            if (AgentCoordinator.isServiceConnected.value) return true
            val expectedComponent = ComponentName(context, TapPilotAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expectedComponent }
        }
    }
}
