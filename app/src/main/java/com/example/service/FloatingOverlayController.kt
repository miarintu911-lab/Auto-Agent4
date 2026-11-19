package com.example.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentUiState
import kotlin.math.abs

class FloatingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var statusDot: View? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var dismissButton: Button? = null
    private var confirmationBox: LinearLayout? = null
    private var confirmationReasonView: TextView? = null

    fun renderState(state: AgentUiState) {
        mainHandler.post {
            when (state) {
                is AgentUiState.Idle -> hideInternal()
                is AgentUiState.Running -> {
                    ensureAttached()
                    updateDotColor("#2EE6A6")
                    val stepHeader = if (state.mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                        "Monitor • Cycle #${state.currentStep}"
                    } else {
                        "TapPilot • Step ${state.currentStep}/${state.maxSteps ?: 0}"
                    }
                    titleView?.text = stepHeader
                    detailView?.text = state.lastThought.ifBlank { state.phase.label }
                    pauseButton?.visibility = View.VISIBLE
                    pauseButton?.text = "Pause"
                    stopButton?.visibility = View.VISIBLE
                    dismissButton?.visibility = View.GONE
                    confirmationBox?.visibility = View.GONE
                }
                is AgentUiState.Paused -> {
                    ensureAttached()
                    updateDotColor("#FFB020")
                    val modeTag = if (state.mode == AgentExecutionMode.CONTINUOUS_MONITOR) "Monitor" else "TapPilot"
                    titleView?.text = "$modeTag Paused • #${state.currentStep}"
                    detailView?.text = state.pauseReason.ifBlank { "Paused. Tap Resume or Stop." }
                    pauseButton?.visibility = View.VISIBLE
                    pauseButton?.text = "Resume"
                    stopButton?.visibility = View.VISIBLE
                    dismissButton?.visibility = View.GONE
                    confirmationBox?.visibility = View.GONE
                }
                is AgentUiState.AwaitingConfirmation -> {
                    ensureAttached()
                    updateDotColor("#FFB020")
                    titleView?.text = "Payment / Security Gate"
                    detailView?.text = state.targetDescription
                    pauseButton?.visibility = View.GONE
                    stopButton?.visibility = View.GONE
                    dismissButton?.visibility = View.GONE
                    confirmationBox?.visibility = View.VISIBLE
                    confirmationReasonView?.text = state.safetyReason
                }
                is AgentUiState.Completed -> {
                    ensureAttached()
                    updateDotColor("#2EE6A6")
                    titleView?.text = "Completed (${state.totalSteps} steps)"
                    detailView?.text = state.summary
                    pauseButton?.visibility = View.GONE
                    stopButton?.visibility = View.GONE
                    dismissButton?.visibility = View.VISIBLE
                    confirmationBox?.visibility = View.GONE
                }
                is AgentUiState.Error -> {
                    ensureAttached()
                    updateDotColor("#FF5468")
                    titleView?.text = "Agent Stopped"
                    detailView?.text = state.message
                    pauseButton?.visibility = View.GONE
                    stopButton?.visibility = View.GONE
                    dismissButton?.visibility = View.VISIBLE
                    confirmationBox?.visibility = View.GONE
                }
            }
        }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        val view = rootView ?: return
        val runnable = Runnable {
            view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    fun setTouchPassthrough(passthrough: Boolean) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val runnable = Runnable {
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            val desiredFlags = if (passthrough) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            }
            if (params.flags != desiredFlags) {
                params.flags = desiredFlags
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    fun destroy() {
        mainHandler.post { hideInternal() }
    }

    private fun ensureAttached() {
        if (rootView != null) {
            rootView?.visibility = View.VISIBLE
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE131A22"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#2EE6A6"))
            }
            elevation = dp(10).toFloat()
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2EE6A6"))
            }
        }
        statusDot = dot

        val title = TextView(context).apply {
            setTextColor(Color.parseColor("#EDF2F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = title

        headerRow.addView(dot)
        headerRow.addView(title)

        val detail = TextView(context).apply {
            setTextColor(Color.parseColor("#8B9BB0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(8))
        }
        detailView = detail

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val pauseBtn = makePillButton("Pause", "#1A232E", "#EDF2F7").apply {
            setOnClickListener {
                when (AgentCoordinator.uiState.value) {
                    is AgentUiState.Paused -> AgentCoordinator.resumeTask()
                    is AgentUiState.Running -> AgentCoordinator.pauseTask()
                    else -> Unit
                }
            }
        }
        pauseButton = pauseBtn

        val stopBtn = makePillButton("Stop", "#3D1018", "#FF5468").apply {
            setOnClickListener { AgentCoordinator.stopTask() }
        }
        stopButton = stopBtn

        val dismissBtn = makePillButton("Close", "#1A232E", "#2EE6A6").apply {
            visibility = View.GONE
            setOnClickListener { AgentCoordinator.dismissFinishedState() }
        }
        dismissButton = dismissBtn

        controlsRow.addView(pauseBtn)
        controlsRow.addView(stopBtn)
        controlsRow.addView(dismissBtn)

        val confirmContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        val confirmReason = TextView(context).apply {
            setTextColor(Color.parseColor("#FFB020"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(8))
        }
        confirmationReasonView = confirmReason

        val confirmButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val approveBtn = makePillButton("Approve", "#0A3828", "#2EE6A6").apply {
            setOnClickListener { AgentCoordinator.confirmPendingAction(true) }
        }
        val cancelBtn = makePillButton("Cancel", "#3D1018", "#FF5468").apply {
            setOnClickListener { AgentCoordinator.confirmPendingAction(false) }
        }
        confirmButtons.addView(cancelBtn)
        confirmButtons.addView(approveBtn)

        confirmContainer.addView(confirmReason)
        confirmContainer.addView(confirmButtons)
        confirmationBox = confirmContainer

        container.addView(headerRow)
        container.addView(detail)
        container.addView(controlsRow)
        container.addView(confirmContainer)

        val params = WindowManager.LayoutParams(
            dp(264),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(90)
        }

        attachDragListener(container, params)

        try {
            windowManager.addView(container, params)
            rootView = container
            layoutParams = params
        } catch (_: Exception) {
            rootView = null
            layoutParams = null
        }
    }

    private fun attachDragListener(target: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        dragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(target, params) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }

    private fun makePillButton(label: String, bgHex: String, textHex: String): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minimumHeight = dp(48)
            minHeight = dp(48)
            minWidth = dp(72)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
        }
    }

    private fun updateDotColor(hex: String) {
        val drawable = statusDot?.background as? GradientDrawable ?: return
        drawable.setColor(Color.parseColor(hex))
    }

    private fun hideInternal() {
        val view = rootView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        rootView = null
        layoutParams = null
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
