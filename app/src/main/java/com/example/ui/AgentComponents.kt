package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AgentUiState
import com.example.agent.AiProvider
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.ui.platform.LocalContext
import com.example.agent.AiRoutingStrategy
import com.example.agent.CachedActionEntry
import com.example.agent.CarromRuleMode
import com.example.agent.SafetyPolicyMode
import com.example.agent.StepStatus
import com.example.agent.TaskPlaybook
import com.example.ai.ApiKeyFailoverManager
import com.example.ai.BulkKeyImporter
import com.example.game.CarromMemoryData
import com.example.game.CarromTurnHudState
import com.example.game.TurnProbeStage
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TelemetryHeader(
    isServiceActive: Boolean,
    isRunning: Boolean,
    onOpenSettings: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val statusColor by animateColorAsState(
        targetValue = if (isServiceActive) CyberTeal else AmberWarning,
        label = "status_color"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceActive) {
                        stringResource(R.string.status_service_active)
                    } else {
                        stringResource(R.string.status_service_inactive)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.hero_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("settings_button")
                .clip(RoundedCornerShape(14.dp))
                .background(SlateSurfaceElevated)
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AccessibilityOnboardingCard(
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AccessibilityNew,
                    contentDescription = stringResource(R.string.cd_status_indicator),
                    tint = AmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberWarning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("enable_accessibility_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Launch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_enable_accessibility),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRefreshStatus,
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh_status),
                        tint = AmberWarning
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandDeckCard(
    goalText: String,
    executionMode: AgentExecutionMode,
    settings: AgentSettings,
    presets: List<GoalPreset>,
    agentState: AgentUiState,
    isServiceActive: Boolean,
    isLiveStreamActive: Boolean = false,
    carromMemory: CarromMemoryData = CarromMemoryData(),
    onGoalChanged: (String) -> Unit,
    onModeChanged: (AgentExecutionMode) -> Unit,
    onSelectPreset: (GoalPreset) -> Unit,
    onToggleLiveStream: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onLaunch: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val isRunning = agentState is AgentUiState.Running
    val isPaused = agentState is AgentUiState.Paused
    val isConfirming = agentState is AgentUiState.AwaitingConfirmation
    val isBusy = isRunning || isPaused || isConfirming
    val providerKeys = settings.apiKeysFor(settings.aiProvider)
    val totalPoolKeys = settings.totalConfiguredKeyCount()
    val keyMissing = settings.aiProvider.requiresApiKey &&
        providerKeys.isEmpty() &&
        (!settings.autoCrossProviderFailover || totalPoolKeys == 0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = CyberTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.command_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (keyMissing) AmberContainer else SlateSurfaceElevated)
                        .border(
                            1.dp,
                            if (keyMissing) AmberWarning else SlateBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isRunning) {
                            if (agentState.mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                                "CYCLE #${agentState.currentStep}"
                            } else {
                                "STEP ${agentState.currentStep}/${agentState.maxSteps ?: 0}"
                            }
                        } else if (keyMissing) {
                            "${settings.aiProvider.shortBadge} • Key Needed"
                        } else if (settings.aiProvider.requiresApiKey && providerKeys.isNotEmpty()) {
                            "${settings.aiProvider.shortBadge} (${providerKeys.size})"
                        } else if (settings.aiProvider.requiresApiKey && totalPoolKeys > 0) {
                            "Failover Pool ($totalPoolKeys)"
                        } else {
                            settings.aiProvider.shortBadge
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (keyMissing) AmberWarning else CyberTeal
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = executionMode == AgentExecutionMode.SINGLE_SESSION,
                    onClick = { if (!isBusy) onModeChanged(AgentExecutionMode.SINGLE_SESSION) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("mode_single_chip"),
                    label = {
                        Text(
                            text = stringResource(R.string.mode_single_session),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberTealDark,
                        selectedLabelColor = CyberTeal,
                        selectedLeadingIconColor = CyberTeal
                    )
                )

                FilterChip(
                    selected = executionMode == AgentExecutionMode.CONTINUOUS_MONITOR,
                    onClick = { if (!isBusy) onModeChanged(AgentExecutionMode.CONTINUOUS_MONITOR) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("mode_monitor_chip"),
                    label = {
                        Text(
                            text = stringResource(R.string.mode_continuous_monitor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AllInclusive,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberTealDark,
                        selectedLabelColor = CyberTeal,
                        selectedLeadingIconColor = CyberTeal
                    )
                )
            }

            if (executionMode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mode_monitor_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ElectricCyan
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = goalText,
                onValueChange = onGoalChanged,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("goal_input_field"),
                label = { Text(stringResource(R.string.command_input_label)) },
                placeholder = { Text(stringResource(R.string.command_input_placeholder)) },
                minLines = 2,
                maxLines = 5,
                trailingIcon = {
                    if (goalText.isNotEmpty() && !isBusy) {
                        IconButton(
                            onClick = { onGoalChanged("") },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_clear_input)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberTeal,
                    unfocusedBorderColor = SlateBorder,
                    focusedLabelColor = CyberTeal
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.presets_label),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    AssistChip(
                        onClick = { if (!isBusy) onSelectPreset(preset) },
                        enabled = !isBusy,
                        label = {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = SlateSurfaceElevated,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, SlateBorder)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val showCarromControls = isLiveStreamActive ||
                goalText.contains("carrom", ignoreCase = true) ||
                goalText.contains("ক্যারম") ||
                goalText.contains("কেরাম") ||
                carromMemory.matchCalibration.shotsThisMatch > 0
            if (showCarromControls) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = isLiveStreamActive,
                        onClick = onToggleLiveStream,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("live_stream_chip"),
                        label = {
                            Text(
                                text = if (isLiveStreamActive) {
                                    stringResource(R.string.live_stream_active_badge)
                                } else {
                                    stringResource(R.string.live_stream_enable_badge)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberTealDark,
                            selectedLabelColor = CyberTeal,
                            selectedLeadingIconColor = CyberTeal
                        )
                    )

                    val cal = carromMemory.matchCalibration
                    if (cal.shotsThisMatch > 0 || cal.sliderSamples > 0 || carromMemory.scratchHeatmap.zoneRiskByKey.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(SlateSurfaceElevated)
                                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("carrom_telemetry_pill")
                        ) {
                            Text(
                                text = "Slider %.2fx • Power %.2fx • Friction %.2fx".format(
                                    cal.sliderGain,
                                    cal.shotPowerGain,
                                    cal.frictionScale
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isBusy) {
                Button(
                    onClick = onLaunch,
                    enabled = isServiceActive && goalText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("launch_agent_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberTeal,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Icon(
                        imageVector = if (executionMode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                            Icons.Rounded.AllInclusive
                        } else {
                            Icons.Rounded.TouchApp
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (executionMode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                            stringResource(R.string.action_start_monitor)
                        } else {
                            stringResource(R.string.action_launch_agent)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { if (isPaused) onResume() else onPause() },
                        enabled = !isConfirming,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("pause_resume_button"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SlateSurfaceElevated,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) {
                                stringResource(R.string.action_resume_agent)
                            } else {
                                stringResource(R.string.action_pause_agent)
                            }
                        )
                    }

                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("stop_agent_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonContainer,
                            contentColor = CrimsonStop
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.action_stop_agent))
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundAutoReplyQuickCard(
    isServiceActive: Boolean,
    isRunningThisMode: Boolean,
    isOtherTaskBusy: Boolean,
    onStartBgAutoReply: () -> Unit,
    onStopBgAutoReply: () -> Unit
) {
    val activeBorderColor by animateColorAsState(
        targetValue = if (isRunningThisMode) CyberTeal else SlateBorder,
        animationSpec = tween(260),
        label = "bgAutoReplyBorder"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunningThisMode) CyberTealDark.copy(alpha = 0.45f) else SlateSurface
        ),
        border = BorderStroke(1.dp, activeBorderColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AllInclusive,
                        contentDescription = null,
                        tint = if (isRunningThisMode) CyberTeal else ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bg_auto_reply_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isRunningThisMode) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberTealDark)
                            .border(1.dp, CyberTeal, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "24/7 LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTeal,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bg_auto_reply_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (isRunningThisMode) onStopBgAutoReply() else onStartBgAutoReply()
                },
                enabled = isRunningThisMode || (isServiceActive && !isOtherTaskBusy),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(50.dp)
                    .testTag("btn_toggle_bg_auto_reply"),
                colors = if (isRunningThisMode) {
                    ButtonDefaults.buttonColors(
                        containerColor = CrimsonContainer,
                        contentColor = CrimsonStop
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextSecondary
                    )
                }
            ) {
                Icon(
                    imageVector = if (isRunningThisMode) Icons.Rounded.Stop else Icons.Rounded.AllInclusive,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunningThisMode) {
                        stringResource(R.string.action_stop_bg_auto_reply)
                    } else {
                        stringResource(R.string.action_start_bg_auto_reply)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun CarromWatchTrainCard(
    isServiceActive: Boolean,
    isRunningThisMode: Boolean,
    isOtherTaskBusy: Boolean,
    carromMemory: CarromMemoryData,
    isLiveStreamActive: Boolean,
    transferStatus: String? = null,
    onStartTraining: () -> Unit,
    onStopTraining: () -> Unit,
    onExportTraining: () -> Unit = {},
    onImportTraining: () -> Unit = {},
    onDismissTransferStatus: () -> Unit = {}
) {
    val activeBorderColor by animateColorAsState(
        targetValue = if (isRunningThisMode) ElectricCyan else SlateBorder,
        animationSpec = tween(260),
        label = "carromWatchTrainBorder"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunningThisMode) CyberTealDark.copy(alpha = 0.42f) else SlateSurface
        ),
        border = BorderStroke(1.dp, activeBorderColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = if (isRunningThisMode) ElectricCyan else CyberTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.carrom_train_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isRunningThisMode) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberTealDark)
                            .border(1.dp, ElectricCyan, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isLiveStreamActive) "WATCH LIVE • 10FPS" else "WATCHING",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.carrom_train_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            val totalAttempts = carromMemory.statsByBucket.values.sumOf { it.attempts }
            val totalPots = carromMemory.statsByBucket.values.sumOf { it.successes }
            val blockedFouls = carromMemory.blockedFoulCount()
            if (totalAttempts > 0 || carromMemory.matchCalibration.shotsThisMatch > 0 || blockedFouls > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceElevated)
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Learned: %d shots (%d pots) • Blocked Fouls: %d • Power %.2fx • Friction %.2fx".format(
                            Locale.US,
                            maxOf(totalAttempts, carromMemory.matchCalibration.shotsThisMatch),
                            totalPots,
                            blockedFouls,
                            carromMemory.matchCalibration.shotPowerGain,
                            carromMemory.matchCalibration.frictionScale
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
            }

            if (!transferStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberTealDark.copy(alpha = 0.55f))
                        .border(1.dp, CyberTeal.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { onDismissTransferStatus() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = transferStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (isRunningThisMode) onStopTraining() else onStartTraining()
                },
                enabled = isRunningThisMode || (isServiceActive && !isOtherTaskBusy),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(50.dp)
                    .testTag("btn_toggle_carrom_train"),
                colors = if (isRunningThisMode) {
                    ButtonDefaults.buttonColors(
                        containerColor = CrimsonContainer,
                        contentColor = CrimsonStop
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = CyberTealDark,
                        contentColor = CyberTeal,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextSecondary
                    )
                },
                border = if (isRunningThisMode) null else BorderStroke(1.dp, CyberTeal.copy(alpha = 0.65f))
            ) {
                Icon(
                    imageVector = if (isRunningThisMode) Icons.Rounded.Stop else Icons.Rounded.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunningThisMode) {
                        stringResource(R.string.action_stop_carrom_train)
                    } else {
                        stringResource(R.string.action_start_carrom_train)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportTraining,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .height(48.dp)
                        .testTag("btn_export_carrom_training"),
                    border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.cd_export_carrom_brain),
                        tint = CyberTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_export_carrom_brain),
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTeal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onImportTraining,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .height(48.dp)
                        .testTag("btn_import_carrom_training"),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = stringResource(R.string.cd_import_carrom_brain),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_import_carrom_brain),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun WorkingMemoryCard(memory: String) {
    if (memory.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "AI WORKING MEMORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = memory,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun PlaybookLibraryCard(
    playbooks: List<TaskPlaybook>,
    onDeletePlaybook: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(playbooks.isNotEmpty()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = CyberTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.playbook_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${playbooks.size} trained",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (playbooks.isEmpty()) TextSecondary else CyberTeal
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (playbooks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.playbook_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        playbooks.take(6).forEach { pb ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SlateSurfaceElevated)
                                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pb.topicTitle,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Trained ${pb.trainingSessions}x • ${pb.winningStrategies.size} tactics • ${pb.foulLessons.size} foul guards",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ElectricCyan
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeletePlaybook(pb.topicKey) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.cd_delete_playbook),
                                            tint = TextSecondary
                                        )
                                    }
                                }
                                if (pb.rulesSummary.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = pb.rulesSummary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (pb.winningStrategies.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    pb.winningStrategies.takeLast(5).forEach { tip ->
                                        Text(
                                            text = "+ $tip",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CyberTeal
                                        )
                                    }
                                }
                                if (pb.foulLessons.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    pb.foulLessons.takeLast(5).forEach { foul ->
                                        Text(
                                            text = "! Avoid: $foul",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AmberWarning
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionCacheLibraryCard(
    cachedActions: List<CachedActionEntry>,
    onDeleteEntry: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val totalReuses = remember(cachedActions) {
        cachedActions.sumOf { (it.hitCount - 1).coerceAtLeast(0) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_cache_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (totalReuses > 0) {
                            "${cachedActions.size} saved • ${totalReuses}x 0-API"
                        } else {
                            "${cachedActions.size} saved"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cachedActions.isEmpty()) TextSecondary else ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (cachedActions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.action_cache_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onClearAll,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_action_cache_button")
                        ) {
                            Text(
                                text = stringResource(R.string.action_clear_cache),
                                color = AmberWarning
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cachedActions.take(8).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SlateSurfaceElevated)
                                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${entry.actionType.name}: ${entry.targetLabel}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${entry.packageName} • Used ${entry.hitCount}x (0 API)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberTeal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteEntry(entry.cacheKey) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_delete_cached_action),
                                        tint = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SensitiveConfirmCard(
    state: AgentUiState.AwaitingConfirmation,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, AmberWarning),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = AmberWarning
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberWarning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.targetDescription,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.safetyReason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, CrimsonStop),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("cancel_sensitive_button")
                ) {
                    Text(
                        text = stringResource(R.string.confirm_cancel),
                        color = CrimsonStop
                    )
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberTeal,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("approve_sensitive_button")
                ) {
                    Text(text = stringResource(R.string.confirm_approve))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentOutcomeBanner(
    state: AgentUiState,
    carromTurnHud: CarromTurnHudState? = null
) {
    when (state) {
        is AgentUiState.Running -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberTealDark.copy(alpha = 0.55f)),
                border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val activeRoute = ApiKeyFailoverManager.getLastRouteLabel()
                    val headerText = if (activeRoute.isNotBlank()) {
                        "${state.phase.label} • $activeRoute"
                    } else {
                        state.phase.label
                    }
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                    if (carromTurnHud != null && carromTurnHud.isActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val stageColor = when (carromTurnHud.stage) {
                            TurnProbeStage.LOCKED_SURE_SHOT -> CyberTeal
                            TurnProbeStage.CAROM_SURE_SHOT,
                            TurnProbeStage.PROBING_SLIDER,
                            TurnProbeStage.PRE_SCAN,
                            TurnProbeStage.WATCH_TRAINING -> ElectricCyan
                            TurnProbeStage.FALLBACK_FIRE -> AmberWarning
                        }
                        val clockColor = if (carromTurnHud.elapsedSec >= 5.5f) AmberWarning else CyberTeal
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("carrom_turn_hud_row")
                        ) {
                            if (carromTurnHud.stage == TurnProbeStage.WATCH_TRAINING) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SlateSurfaceElevated)
                                        .border(1.dp, ElectricCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "SHOTS ${carromTurnHud.probeNumber} LEARNED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SlateSurfaceElevated)
                                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${carromTurnHud.scanCount} POTS • ${carromTurnHud.rejectedCount} FOULS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SlateSurfaceElevated)
                                        .border(1.dp, clockColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "CLOCK %.1fs / %.1fs".format(carromTurnHud.elapsedSec, carromTurnHud.maxSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = clockColor
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SlateSurfaceElevated)
                                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "PROBE ${carromTurnHud.probeNumber}/${carromTurnHud.maxProbes} • ${carromTurnHud.rejectedCount} REJ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(stageColor.copy(alpha = 0.16f))
                                    .border(1.dp, stageColor, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = carromTurnHud.stage.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stageColor
                                )
                            }
                        }
                    }
                    if (state.lastThought.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.lastThought,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        is AgentUiState.Completed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberTealDark.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, CyberTeal)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = CyberTeal
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Completed in ${state.totalSteps} step(s)",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberTeal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.summary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        is AgentUiState.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CrimsonContainer.copy(alpha = 0.65f)),
                border = BorderStroke(1.dp, CrimsonStop)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = CrimsonStop
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Execution Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = CrimsonStop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
fun StepLogCard(log: AgentStepLog) {
    val badgeColor = when (log.status) {
        StepStatus.EXECUTED, StepStatus.APPROVED, StepStatus.COMPLETED -> CyberTeal
        StepStatus.WAITING -> ElectricCyan
        StepStatus.AWAITING_APPROVAL -> AmberWarning
        StepStatus.REJECTED -> AmberWarning
        StepStatus.FAILED -> CrimsonStop
    }
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeLabel = formatter.format(Date(log.timestampMs))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${log.stepNumber} ${log.actionType.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                    if (log.requiresConfirmation) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = "$timeLabel • ${log.status.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = log.targetSummary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = log.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun EmptyTraceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.trace_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.trace_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentSettingsSheet(
    settings: AgentSettings,
    isLiveStreamActive: Boolean = false,
    onToggleLiveStream: () -> Unit = {},
    onUpdate: ((AgentSettings) -> AgentSettings) -> Unit,
    onResetKeyHealth: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var healthResetTick by remember { mutableStateOf(0) }
    var bulkImportNotice by remember { mutableStateOf<String?>(null) }

    val provider = settings.aiProvider
    var localKeyBlock by remember(provider) { mutableStateOf(settings.rawKeyBlockFor(provider)) }
    var localModel by remember(provider) { mutableStateOf(settings.modelNameFor(provider)) }
    val parsedKeys = remember(localKeyBlock) { AgentSettings.parseApiKeys(localKeyBlock) }
    val failedCount = remember(provider, healthResetTick) {
        ApiKeyFailoverManager.getFailedCount(provider)
    }
    val cooldownCount = remember(provider, healthResetTick) {
        ApiKeyFailoverManager.getCooldownCount(provider)
    }
    val activeSlot = remember(provider, parsedKeys.size, healthResetTick) {
        if (parsedKeys.isNotEmpty()) {
            ApiKeyFailoverManager.getActiveKeyPosition(provider, parsedKeys.size) + 1
        } else {
            0
        }
    }

    val bulkKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val rawContent = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrNull()

        if (rawContent.isNullOrBlank()) {
            bulkImportNotice = "Could not read keys from selected file."
            return@rememberLauncherForActivityResult
        }

        val (updatedSettings, summary) = BulkKeyImporter.importIntoSettings(
            rawContent = rawContent,
            currentSettings = settings,
            fallbackProvider = provider
        )
        if (summary.totalImported == 0) {
            bulkImportNotice = "No valid API keys found in file."
        } else {
            onUpdate { updatedSettings }
            localKeyBlock = updatedSettings.rawKeyBlockFor(provider)
            bulkImportNotice = "Imported ${summary.totalImported} key(s) (+${summary.newlyAddedCount} new): " +
                "AI Studio ${summary.googleCount}, Groq ${summary.groqCount}, OpenRouter ${summary.openRouterCount}"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall
            )
            HorizontalDivider(color = SlateBorder)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = CyberTeal,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_provider_section),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AiProvider.entries.forEach { item ->
                    FilterChip(
                        selected = provider == item,
                        onClick = { onUpdate { it.copy(aiProvider = item) } },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("provider_${item.name.lowercase()}"),
                        label = { Text(item.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberTealDark,
                            selectedLabelColor = CyberTeal
                        )
                    )
                }
            }

            if (!provider.requiresApiKey) {
                Text(
                    text = stringResource(R.string.settings_firebase_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ElectricCyan
                )
            } else {
                OutlinedTextField(
                    value = localKeyBlock,
                    onValueChange = { newVal ->
                        localKeyBlock = newVal
                        onUpdate { current ->
                            when (provider) {
                                AiProvider.GOOGLE_AI_STUDIO -> current.copy(googleAiStudioApiKey = newVal)
                                AiProvider.GROQ_CLOUD -> current.copy(groqApiKey = newVal)
                                AiProvider.OPENROUTER -> current.copy(openRouterApiKey = newVal)
                                AiProvider.FIREBASE_AI -> current
                            }
                        }
                    },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input"),
                    label = { Text("${provider.displayName} ${stringResource(R.string.settings_api_key_label)}") },
                    placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
                    trailingIcon = {
                        IconButton(
                            onClick = { showApiKey = !showApiKey },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_key_visibility)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = CyberTeal
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            bulkKeyFileLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                        },
                        border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("bulk_upload_keys_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = null,
                            tint = CyberTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_bulk_upload_file),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = CyberTeal
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val (sortedSettings, summary) = BulkKeyImporter.redistributeMixedBlock(
                                mixedText = localKeyBlock,
                                currentSettings = settings,
                                sourceProvider = provider
                            )
                            onUpdate { sortedSettings }
                            localKeyBlock = sortedSettings.rawKeyBlockFor(provider)
                            bulkImportNotice = if (summary.totalImported == 0) {
                                "Paste mixed keys above first to auto-sort."
                            } else {
                                "Sorted ${summary.totalImported} key(s): AI Studio ${summary.googleCount}, Groq ${summary.groqCount}, OpenRouter ${summary.openRouterCount}"
                            }
                        },
                        enabled = localKeyBlock.isNotBlank(),
                        border = BorderStroke(1.dp, SlateBorder),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("auto_sort_keys_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_bulk_auto_sort),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = ElectricCyan
                        )
                    }
                }

                bulkImportNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricCyan
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val statusText = if (parsedKeys.isEmpty()) {
                        "0 keys loaded (Pool total: ${settings.totalConfiguredKeyCount()})"
                    } else {
                        val parts = mutableListOf("${parsedKeys.size} ready", "Cursor #$activeSlot")
                        if (cooldownCount > 0) parts.add("$cooldownCount in 65s cooldown")
                        if (failedCount > 0) parts.add("$failedCount invalid")
                        parts.joinToString(" • ")
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (parsedKeys.isEmpty()) AmberWarning else CyberTeal,
                        modifier = Modifier.weight(1f)
                    )
                    if (failedCount > 0 || cooldownCount > 0 || parsedKeys.size > 1) {
                        TextButton(
                            onClick = {
                                onResetKeyHealth()
                                healthResetTick++
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reset_key_health_button")
                        ) {
                            Text(
                                text = stringResource(R.string.settings_reset_key_health),
                                color = ElectricCyan
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_free_models_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    provider.freeModels.forEach { option ->
                        FilterChip(
                            selected = localModel.trim() == option.id,
                            onClick = {
                                localModel = option.id
                                onUpdate { current ->
                                    when (provider) {
                                        AiProvider.GOOGLE_AI_STUDIO -> current.copy(googleAiStudioModel = option.id)
                                        AiProvider.GROQ_CLOUD -> current.copy(groqModel = option.id)
                                        AiProvider.OPENROUTER -> current.copy(openRouterModel = option.id)
                                        AiProvider.FIREBASE_AI -> current
                                    }
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("free_model_${option.id}"),
                            label = {
                                Text(
                                    text = option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberTealDark,
                                selectedLabelColor = CyberTeal
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = localModel,
                    onValueChange = { newModel ->
                        localModel = newModel
                        onUpdate { current ->
                            when (provider) {
                                AiProvider.GOOGLE_AI_STUDIO -> current.copy(googleAiStudioModel = newModel)
                                AiProvider.GROQ_CLOUD -> current.copy(groqModel = newModel)
                                AiProvider.OPENROUTER -> current.copy(openRouterModel = newModel)
                                AiProvider.FIREBASE_AI -> current
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_id_input"),
                    label = { Text(stringResource(R.string.settings_model_id_label)) },
                    placeholder = { Text(provider.defaultModel) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = CyberTeal
                    )
                )

                if (parsedKeys.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_api_key_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberWarning
                    )
                }
            }

            HorizontalDivider(color = SlateBorder)

            Text(
                text = stringResource(R.string.settings_routing_section),
                style = MaterialTheme.typography.titleMedium
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AiRoutingStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = settings.routingStrategy == strategy,
                        onClick = { onUpdate { it.copy(routingStrategy = strategy) } },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("routing_${strategy.name.lowercase()}"),
                        label = { Text(strategy.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberTealDark,
                            selectedLabelColor = CyberTeal
                        )
                    )
                }
            }

            Text(
                text = if (settings.routingStrategy == AiRoutingStrategy.SMART_HYBRID) {
                    stringResource(R.string.settings_routing_hybrid_desc)
                } else {
                    stringResource(R.string.settings_routing_sticky_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_cross_failover_label),
                subtitle = stringResource(R.string.settings_cross_failover_desc),
                checked = settings.autoCrossProviderFailover,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(autoCrossProviderFailover = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_smart_fast_path_label),
                subtitle = stringResource(R.string.settings_smart_fast_path_desc),
                checked = settings.smartTextFastPath,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(smartTextFastPath = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_turbo_hedge_label),
                subtitle = stringResource(R.string.settings_turbo_hedge_desc),
                checked = settings.turboDualKeyGameMode,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(turboDualKeyGameMode = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_zero_api_cache_label),
                subtitle = stringResource(R.string.settings_zero_api_cache_desc),
                checked = settings.useZeroApiActionCache,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useZeroApiActionCache = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_live_projection_label),
                subtitle = stringResource(R.string.settings_live_projection_desc),
                checked = isLiveStreamActive,
                testTag = "switch_live_projection",
                onCheckedChange = { _ -> onToggleLiveStream() }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_carrom_solver_label),
                subtitle = stringResource(R.string.settings_carrom_solver_desc),
                checked = settings.carromPrecisionSolver,
                testTag = "switch_carrom_solver",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(carromPrecisionSolver = enabled) }
                }
            )

            Text(
                text = stringResource(R.string.settings_carrom_rule_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CarromRuleMode.entries.forEach { ruleMode ->
                    FilterChip(
                        selected = settings.carromRuleMode == ruleMode,
                        onClick = { onUpdate { it.copy(carromRuleMode = ruleMode) } },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("carrom_rule_${ruleMode.name.lowercase()}"),
                        label = { Text(ruleMode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberTealDark,
                            selectedLabelColor = CyberTeal
                        )
                    )
                }
            }

            Text(
                text = if (settings.carromRuleMode == CarromRuleMode.FREESTYLE) {
                    stringResource(R.string.settings_carrom_rule_freestyle_desc)
                } else {
                    stringResource(R.string.settings_carrom_rule_discpool_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_physics_rollout_label),
                subtitle = stringResource(R.string.settings_physics_rollout_desc),
                checked = settings.usePhysicsRollout,
                testTag = "switch_physics_rollout",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(usePhysicsRollout = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_subpixel_circle_label),
                subtitle = stringResource(R.string.settings_subpixel_circle_desc),
                checked = settings.useSubPixelCircleRefinement,
                testTag = "switch_subpixel_circle",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useSubPixelCircleRefinement = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_closed_loop_slider_label),
                subtitle = stringResource(R.string.settings_closed_loop_slider_desc),
                checked = settings.useClosedLoopSliderVerify,
                testTag = "switch_closed_loop_slider",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useClosedLoopSliderVerify = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_coordinate_grid_label),
                subtitle = stringResource(R.string.settings_coordinate_grid_desc),
                checked = settings.useCoordinateGridOverlay,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useCoordinateGridOverlay = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_set_of_mark_label),
                subtitle = stringResource(R.string.settings_set_of_mark_desc),
                checked = settings.useSetOfMarkOverlay,
                testTag = "switch_set_of_mark_overlay",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useSetOfMarkOverlay = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_vision_label),
                subtitle = stringResource(R.string.settings_vision_desc),
                checked = settings.useVisionScreenshot,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useVisionScreenshot = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_research_label),
                subtitle = stringResource(R.string.settings_research_desc),
                checked = settings.autoResearchBeforeTask,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(autoResearchBeforeTask = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_prevent_double_text_label),
                subtitle = stringResource(R.string.settings_prevent_double_text_desc),
                checked = settings.preventDoubleText,
                testTag = "switch_prevent_double_text",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(preventDoubleText = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_saved_contacts_only_label),
                subtitle = stringResource(R.string.settings_saved_contacts_only_desc),
                checked = settings.autoReplySavedContactsOnly,
                testTag = "switch_saved_contacts_only",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(autoReplySavedContactsOnly = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_reply_only_notif_label),
                subtitle = stringResource(R.string.settings_reply_only_notif_desc),
                checked = settings.replyOnlyOnMessageNotifications,
                testTag = "switch_reply_only_on_notifications",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(replyOnlyOnMessageNotifications = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_safety_mode_label),
                subtitle = stringResource(R.string.settings_safety_mode_desc),
                checked = settings.safetyPolicyMode == SafetyPolicyMode.PAYMENT_ONLY,
                onCheckedChange = { paymentOnly ->
                    onUpdate {
                        it.copy(
                            safetyPolicyMode = if (paymentOnly) {
                                SafetyPolicyMode.PAYMENT_ONLY
                            } else {
                                SafetyPolicyMode.STRICT_ALL
                            }
                        )
                    }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_home_label),
                subtitle = stringResource(R.string.settings_home_desc),
                checked = settings.goHomeOnLaunch,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(goHomeOnLaunch = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_screen_label),
                subtitle = stringResource(R.string.settings_keep_screen_desc),
                checked = settings.keepScreenAwake,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(keepScreenAwake = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_floating_overlay_label),
                subtitle = stringResource(R.string.settings_floating_overlay_desc),
                checked = settings.showFloatingOverlay,
                testTag = "switch_floating_overlay",
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(showFloatingOverlay = enabled) }
                }
            )

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_monitor_interval_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${settings.monitorIntervalSec} sec",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
                Slider(
                    value = settings.monitorIntervalSec.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(monitorIntervalSec = (value / 10f).roundToInt() * 10) }
                    },
                    valueRange = 10f..180f,
                    steps = 16,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberTeal,
                        activeTrackColor = CyberTeal
                    )
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_max_steps_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${settings.maxSteps} steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
                Slider(
                    value = settings.maxSteps.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(maxSteps = value.roundToInt()) }
                    },
                    valueRange = 5f..50f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberTeal,
                        activeTrackColor = CyberTeal
                    )
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_delay_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${settings.stepDelayMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
                Slider(
                    value = settings.stepDelayMs.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(stepDelayMs = (value / 200f).roundToInt() * 200L) }
                    },
                    valueRange = 600f..3000f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberTeal,
                        activeTrackColor = CyberTeal
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = CyberTeal
            )
        )
    }
}
