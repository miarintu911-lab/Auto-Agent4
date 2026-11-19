package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AgentKeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startForegroundWithState(AgentCoordinator.uiState.value)

        stateObserverJob = serviceScope.launch {
            AgentCoordinator.uiState.collectLatest { state ->
                when (state) {
                    is AgentUiState.Idle,
                    is AgentUiState.Completed,
                    is AgentUiState.Error -> stopSelfSafely()
                    else -> updateNotification(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITOR -> {
                AgentCoordinator.stopTask()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_MONITOR -> {
                AgentCoordinator.pauseTask("Paused from notification panel.")
                updateNotification(AgentCoordinator.uiState.value)
                return START_STICKY
            }
            ACTION_RESUME_MONITOR -> {
                AgentCoordinator.resumeTask()
                updateNotification(AgentCoordinator.uiState.value)
                return START_STICKY
            }
            ACTION_APPROVE_CONFIRM -> {
                AgentCoordinator.confirmPendingAction(true)
                updateNotification(AgentCoordinator.uiState.value)
                return START_STICKY
            }
            ACTION_CANCEL_CONFIRM -> {
                AgentCoordinator.confirmPendingAction(false)
                updateNotification(AgentCoordinator.uiState.value)
                return START_STICKY
            }
        }
        startForegroundWithState(AgentCoordinator.uiState.value)
        return START_STICKY
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithState(state: AgentUiState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: AgentUiState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AgentKeepAliveService::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(state: AgentUiState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = serviceActionIntent(ACTION_STOP_MONITOR, 101)
        val pauseIntent = serviceActionIntent(ACTION_PAUSE_MONITOR, 102)
        val resumeIntent = serviceActionIntent(ACTION_RESUME_MONITOR, 103)
        val approveIntent = serviceActionIntent(ACTION_APPROVE_CONFIRM, 104)
        val cancelIntent = serviceActionIntent(ACTION_CANCEL_CONFIRM, 105)

        val (title, body) = when (state) {
            is AgentUiState.Running -> {
                val modeTag = if (state.mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                    "Monitor • Cycle #${state.currentStep}"
                } else {
                    "TapPilot • Step ${state.currentStep}/${state.maxSteps ?: 0}"
                }
                modeTag to state.lastThought.ifBlank { state.phase.label }
            }
            is AgentUiState.Paused -> {
                val modeTag = if (state.mode == AgentExecutionMode.CONTINUOUS_MONITOR) "Monitor" else "TapPilot"
                "$modeTag Paused • #${state.currentStep}" to state.pauseReason.ifBlank { "Paused. Tap Resume or Stop." }
            }
            is AgentUiState.AwaitingConfirmation -> {
                "Payment / Security Gate • Approval Needed" to "${state.targetDescription} — ${state.safetyReason}"
            }
            else -> {
                "TapPilot Monitor Active" to "Listening for screen updates and notifications..."
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)

        when (state) {
            is AgentUiState.Paused -> {
                builder.addAction(0, getString(R.string.action_resume_agent), resumeIntent)
                builder.addAction(0, getString(R.string.action_stop_agent), stopIntent)
            }
            is AgentUiState.AwaitingConfirmation -> {
                builder.addAction(0, getString(R.string.confirm_approve), approveIntent)
                builder.addAction(0, getString(R.string.confirm_cancel), cancelIntent)
            }
            else -> {
                builder.addAction(0, getString(R.string.action_pause_agent), pauseIntent)
                builder.addAction(0, getString(R.string.action_stop_agent), stopIntent)
            }
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.monitor_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapPilot::MonitorWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // Safety 12h cap; released immediately on stop/destroy
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "tappilot_monitor_channel"
        private const val NOTIFICATION_ID = 4041
        private const val ACTION_STOP_MONITOR = "com.example.action.STOP_MONITOR"
        private const val ACTION_PAUSE_MONITOR = "com.example.action.PAUSE_MONITOR"
        private const val ACTION_RESUME_MONITOR = "com.example.action.RESUME_MONITOR"
        private const val ACTION_APPROVE_CONFIRM = "com.example.action.APPROVE_CONFIRM"
        private const val ACTION_CANCEL_CONFIRM = "com.example.action.CANCEL_CONFIRM"

        fun start(context: Context) {
            val intent = Intent(context, AgentKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentKeepAliveService::class.java))
        }
    }
}
