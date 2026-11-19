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
import com.example.agent.NotificationSignal
import com.example.agent.PlannedAction
import com.example.agent.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
                AgentCoordinator.uiState.collectLatest { state ->
                    overlayController?.renderState(state)
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

            val notifText = extractNotificationText(event)
            if (notifText.isNotBlank()) {
                AgentCoordinator.onNotificationReceived(
                    NotificationSignal(
                        packageName = sourcePkg,
                        text = notifText.take(240),
                        timestampMs = System.currentTimeMillis()
                    )
                )
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

    private fun extractNotificationText(event: AccessibilityEvent): String {
        val parcel = event.parcelableData
        if (parcel is Notification) {
            val title = parcel.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = parcel.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val combined = listOf(title, body).filter { it.isNotBlank() }.joinToString(": ")
            if (combined.isNotBlank()) return combined
        }
        return event.text?.joinToString(" ") { it?.toString().orEmpty() }?.trim().orEmpty()
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
