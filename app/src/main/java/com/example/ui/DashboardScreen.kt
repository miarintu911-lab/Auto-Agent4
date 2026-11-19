package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentStepLog
import com.example.agent.AgentUiState
import com.example.agent.ContactProfile
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder

@Composable
fun DashboardScreen(viewModel: AgentViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isServiceConnected.collectAsStateWithLifecycle()
    val isLiveStreamActive by viewModel.isLiveStreamActive.collectAsStateWithLifecycle()
    val carromMemory by viewModel.carromMemory.collectAsStateWithLifecycle()
    val carromTurnHud by viewModel.carromTurnHud.collectAsStateWithLifecycle()
    val isEnabledInSettings by viewModel.isAccessibilityEnabledInSettings.collectAsStateWithLifecycle()
    val goalText by viewModel.goalText.collectAsStateWithLifecycle()
    val executionMode by viewModel.executionMode.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playbooks by viewModel.playbooks.collectAsStateWithLifecycle()
    val cachedActions by viewModel.cachedActions.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()

    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactProfile?>(null) }
    var autoRequestedProjectionThisSession by rememberSaveable { mutableStateOf(false) }
    var launchPendingAfterProjection by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startLiveProjection(result.resultCode, data)
        }
        if (launchPendingAfterProjection) {
            launchPendingAfterProjection = false
            viewModel.launchAgent()
        }
    }

    fun proceedWithLaunchOrProjection() {
        val isCarromGoal = com.example.agent.CarromContextHelper.isCarromGoalOrApp(
            goal = goalText,
            packageName = "",
            playbookTopicKey = null
        )
        if (isCarromGoal && !isLiveStreamActive && !autoRequestedProjectionThisSession) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (manager != null) {
                autoRequestedProjectionThisSession = true
                launchPendingAfterProjection = true
                mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                return
            }
        }
        viewModel.launchAgent()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedWithLaunchOrProjection()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val serviceActive = isConnected || isEnabledInSettings
    val (trace, workingMemory) = when (val s = agentState) {
        is AgentUiState.Idle -> emptyList<AgentStepLog>() to ""
        is AgentUiState.Running -> s.trace to s.workingMemory
        is AgentUiState.Paused -> s.trace to s.workingMemory
        is AgentUiState.AwaitingConfirmation -> s.trace to s.workingMemory
        is AgentUiState.Completed -> s.trace to s.workingMemory
        is AgentUiState.Error -> s.trace to s.workingMemory
    }

    fun handleLaunchClick() {
        if (executionMode == AgentExecutionMode.CONTINUOUS_MONITOR &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        proceedWithLaunchOrProjection()
    }

    fun toggleLiveStream() {
        if (isLiveStreamActive) {
            viewModel.stopLiveProjection()
        } else {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return
            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                CyberTeal.copy(alpha = 0.08f),
                                ElectricCyan.copy(alpha = 0.03f),
                                ObsidianBg
                            ),
                            center = Offset(size.width * 0.85f, size.height * 0.1f),
                            radius = size.maxDimension * 0.7f
                        )
                    )
                    val stepPx = 44.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(
                            color = SlateBorder.copy(alpha = 0.16f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        x += stepPx
                    }
                }
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    TelemetryHeader(
                        isServiceActive = serviceActive,
                        isRunning = agentState is AgentUiState.Running,
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }

                if (!serviceActive) {
                    item {
                        AccessibilityOnboardingCard(
                            onOpenAccessibilitySettings = {
                                context.startActivity(viewModel.buildOpenAccessibilitySettingsIntent())
                            },
                            onRefreshStatus = { viewModel.refreshAccessibilityStatus() }
                        )
                    }
                }

                item {
                    CommandDeckCard(
                        goalText = goalText,
                        executionMode = executionMode,
                        settings = settings,
                        presets = viewModel.quickPresets,
                        agentState = agentState,
                        isServiceActive = serviceActive,
                        isLiveStreamActive = isLiveStreamActive,
                        carromMemory = carromMemory,
                        onGoalChanged = viewModel::onGoalTextChanged,
                        onModeChanged = viewModel::onExecutionModeChanged,
                        onSelectPreset = viewModel::selectPreset,
                        onToggleLiveStream = ::toggleLiveStream,
                        onOpenSettings = { showSettingsSheet = true },
                        onLaunch = ::handleLaunchClick,
                        onPause = viewModel::pauseAgent,
                        onResume = viewModel::resumeAgent,
                        onStop = viewModel::stopAgent
                    )
                }

                val confirmingState = agentState as? AgentUiState.AwaitingConfirmation
                if (confirmingState != null) {
                    item {
                        SensitiveConfirmCard(
                            state = confirmingState,
                            onApprove = { viewModel.confirmPendingAction(true) },
                            onCancel = { viewModel.confirmPendingAction(false) }
                        )
                    }
                }

                item {
                    AgentOutcomeBanner(
                        state = agentState,
                        carromTurnHud = carromTurnHud
                    )
                }

                if (workingMemory.isNotBlank()) {
                    item {
                        WorkingMemoryCard(memory = workingMemory)
                    }
                }

                item {
                    ContactProfilesCard(
                        contacts = contacts,
                        onAddClick = {
                            editingContact = null
                            showContactDialog = true
                        },
                        onEditClick = { contact ->
                            editingContact = contact
                            showContactDialog = true
                        },
                        onDeleteClick = viewModel::deleteContact
                    )
                }

                item {
                    PlaybookLibraryCard(
                        playbooks = playbooks,
                        onDeletePlaybook = viewModel::deletePlaybook
                    )
                }

                item {
                    ActionCacheLibraryCard(
                        cachedActions = cachedActions,
                        onDeleteEntry = viewModel::deleteCachedAction,
                        onClearAll = viewModel::clearActionCache
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.trace_section_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (agentState is AgentUiState.Completed || agentState is AgentUiState.Error) {
                            TextButton(
                                onClick = viewModel::clearTrace,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("clear_trace_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.action_clear_trace),
                                    color = CyberTeal
                                )
                            }
                        }
                    }
                }

                if (trace.isEmpty()) {
                    item {
                        EmptyTraceCard()
                    }
                } else {
                    items(
                        items = trace.asReversed(),
                        key = { "${it.stepNumber}_${it.timestampMs}" }
                    ) { log ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(220)) + slideInVertically { it / 4 }
                        ) {
                            StepLogCard(log = log)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showSettingsSheet) {
        AgentSettingsSheet(
            settings = settings,
            isLiveStreamActive = isLiveStreamActive,
            onToggleLiveStream = ::toggleLiveStream,
            onUpdate = viewModel::updateSettings,
            onResetKeyHealth = viewModel::resetKeyFailoverHealth,
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showContactDialog) {
        AddEditContactDialog(
            initialContact = editingContact,
            onDismiss = {
                showContactDialog = false
                editingContact = null
            },
            onSave = { savedProfile ->
                viewModel.upsertContact(savedProfile)
                showContactDialog = false
                editingContact = null
            }
        )
    }
}
