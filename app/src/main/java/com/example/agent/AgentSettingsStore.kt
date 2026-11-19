package com.example.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tappilot_agent_settings"
)

class AgentSettingsStore(private val context: Context) {

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val GOOGLE_AI_STUDIO_KEY = stringPreferencesKey("google_ai_studio_key")
        val GOOGLE_AI_STUDIO_MODEL = stringPreferencesKey("google_ai_studio_model")
        val GROQ_KEY = stringPreferencesKey("groq_key")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val LEGACY_GROK_KEY = stringPreferencesKey("grok_key")
        val LEGACY_GROK_MODEL = stringPreferencesKey("grok_model")
        val OPENROUTER_KEY = stringPreferencesKey("openrouter_key")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val AUTO_CROSS_PROVIDER_FAILOVER = booleanPreferencesKey("auto_cross_provider_failover")
        val ROUTING_STRATEGY = stringPreferencesKey("routing_strategy")
        val SMART_TEXT_FAST_PATH = booleanPreferencesKey("smart_text_fast_path")
        val TURBO_DUAL_KEY_GAME_MODE = booleanPreferencesKey("turbo_dual_key_game_mode")
        val USE_ZERO_API_ACTION_CACHE = booleanPreferencesKey("use_zero_api_action_cache")
        val CARROM_PRECISION_SOLVER = booleanPreferencesKey("carrom_precision_solver")
        val CARROM_RULE_MODE = stringPreferencesKey("carrom_rule_mode")
        val USE_PHYSICS_ROLLOUT = booleanPreferencesKey("use_physics_rollout")
        val USE_SUBPIXEL_CIRCLE_REFINEMENT = booleanPreferencesKey("use_subpixel_circle_refinement")
        val USE_CLOSED_LOOP_SLIDER_VERIFY = booleanPreferencesKey("use_closed_loop_slider_verify")
        val USE_COORDINATE_GRID_OVERLAY = booleanPreferencesKey("use_coordinate_grid_overlay")
        val USE_SET_OF_MARK_OVERLAY = booleanPreferencesKey("use_set_of_mark_overlay")
        val PREVENT_DOUBLE_TEXT = booleanPreferencesKey("prevent_double_text")
        val AUTO_REPLY_SAVED_CONTACTS_ONLY = booleanPreferencesKey("auto_reply_saved_contacts_only")
        val REPLY_ONLY_ON_NOTIFICATIONS = booleanPreferencesKey("reply_only_on_message_notifications")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val MONITOR_INTERVAL_SEC = intPreferencesKey("monitor_interval_sec")
        val USE_VISION = booleanPreferencesKey("use_vision_screenshot")
        val AUTO_RESEARCH = booleanPreferencesKey("auto_research_before_task")
        val SAFETY_POLICY_MODE = stringPreferencesKey("safety_policy_mode")
        val GO_HOME_ON_LAUNCH = booleanPreferencesKey("go_home_on_launch")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val SHOW_FLOATING_OVERLAY = booleanPreferencesKey("show_floating_overlay")
        val STEP_DELAY_MS = longPreferencesKey("step_delay_ms")
    }

    val settingsFlow: Flow<AgentSettings> = context.agentSettingsDataStore.data.map { prefs ->
        readSettings(prefs)
    }

    suspend fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        context.agentSettingsDataStore.edit { prefs ->
            val current = readSettings(prefs)
            val updated = transform(current)
            prefs[Keys.AI_PROVIDER] = updated.aiProvider.name
            prefs[Keys.GOOGLE_AI_STUDIO_KEY] = updated.googleAiStudioApiKey.trim()
            prefs[Keys.GOOGLE_AI_STUDIO_MODEL] = updated.googleAiStudioModel.trim()
                .ifBlank { AiProvider.GOOGLE_AI_STUDIO.defaultModel }
            prefs[Keys.GROQ_KEY] = updated.groqApiKey.trim()
            prefs[Keys.GROQ_MODEL] = updated.groqModel.trim()
                .ifBlank { AiProvider.GROQ_CLOUD.defaultModel }
            prefs[Keys.OPENROUTER_KEY] = updated.openRouterApiKey.trim()
            prefs[Keys.OPENROUTER_MODEL] = updated.openRouterModel.trim()
                .ifBlank { AiProvider.OPENROUTER.defaultModel }
            prefs[Keys.AUTO_CROSS_PROVIDER_FAILOVER] = updated.autoCrossProviderFailover
            prefs[Keys.ROUTING_STRATEGY] = updated.routingStrategy.name
            prefs[Keys.SMART_TEXT_FAST_PATH] = updated.smartTextFastPath
            prefs[Keys.TURBO_DUAL_KEY_GAME_MODE] = updated.turboDualKeyGameMode
            prefs[Keys.USE_ZERO_API_ACTION_CACHE] = updated.useZeroApiActionCache
            prefs[Keys.CARROM_PRECISION_SOLVER] = updated.carromPrecisionSolver
            prefs[Keys.CARROM_RULE_MODE] = updated.carromRuleMode.name
            prefs[Keys.USE_PHYSICS_ROLLOUT] = updated.usePhysicsRollout
            prefs[Keys.USE_SUBPIXEL_CIRCLE_REFINEMENT] = updated.useSubPixelCircleRefinement
            prefs[Keys.USE_CLOSED_LOOP_SLIDER_VERIFY] = updated.useClosedLoopSliderVerify
            prefs[Keys.USE_COORDINATE_GRID_OVERLAY] = updated.useCoordinateGridOverlay
            prefs[Keys.USE_SET_OF_MARK_OVERLAY] = updated.useSetOfMarkOverlay
            prefs[Keys.PREVENT_DOUBLE_TEXT] = updated.preventDoubleText
            prefs[Keys.AUTO_REPLY_SAVED_CONTACTS_ONLY] = updated.autoReplySavedContactsOnly
            prefs[Keys.REPLY_ONLY_ON_NOTIFICATIONS] = updated.replyOnlyOnMessageNotifications
            prefs[Keys.MAX_STEPS] = updated.maxSteps.coerceIn(5, 50)
            prefs[Keys.MONITOR_INTERVAL_SEC] = updated.monitorIntervalSec.coerceIn(10, 300)
            prefs[Keys.USE_VISION] = updated.useVisionScreenshot
            prefs[Keys.AUTO_RESEARCH] = updated.autoResearchBeforeTask
            prefs[Keys.SAFETY_POLICY_MODE] = updated.safetyPolicyMode.name
            prefs[Keys.GO_HOME_ON_LAUNCH] = updated.goHomeOnLaunch
            prefs[Keys.KEEP_SCREEN_AWAKE] = updated.keepScreenAwake
            prefs[Keys.SHOW_FLOATING_OVERLAY] = updated.showFloatingOverlay
            prefs[Keys.STEP_DELAY_MS] = updated.stepDelayMs.coerceIn(400L, 4000L)
        }
    }

    private fun readSettings(prefs: Preferences): AgentSettings {
        val rawProvider = prefs[Keys.AI_PROVIDER]
        val provider = when (rawProvider) {
            "XAI_GROK" -> AiProvider.GROQ_CLOUD
            null -> AiProvider.GOOGLE_AI_STUDIO
            else -> runCatching { AiProvider.valueOf(rawProvider) }.getOrNull()
                ?: AiProvider.GOOGLE_AI_STUDIO
        }
        val safetyMode = prefs[Keys.SAFETY_POLICY_MODE]
            ?.let { runCatching { SafetyPolicyMode.valueOf(it) }.getOrNull() }
            ?: SafetyPolicyMode.PAYMENT_ONLY
        val routingStrategy = prefs[Keys.ROUTING_STRATEGY]
            ?.let { runCatching { AiRoutingStrategy.valueOf(it) }.getOrNull() }
            ?: AiRoutingStrategy.SMART_HYBRID

        val rawGroqKey = prefs[Keys.GROQ_KEY] ?: prefs[Keys.LEGACY_GROK_KEY].orEmpty()
        val rawGroqModel = (prefs[Keys.GROQ_MODEL] ?: prefs[Keys.LEGACY_GROK_MODEL])
            ?.takeIf { it.isNotBlank() && !it.startsWith("grok-") }
            ?: AiProvider.GROQ_CLOUD.defaultModel

        val carromRuleMode = prefs[Keys.CARROM_RULE_MODE]
            ?.let { runCatching { CarromRuleMode.valueOf(it) }.getOrNull() }
            ?: CarromRuleMode.FREESTYLE

        return AgentSettings(
            aiProvider = provider,
            googleAiStudioApiKey = prefs[Keys.GOOGLE_AI_STUDIO_KEY].orEmpty(),
            googleAiStudioModel = prefs[Keys.GOOGLE_AI_STUDIO_MODEL]
                ?.takeIf { it.isNotBlank() } ?: AiProvider.GOOGLE_AI_STUDIO.defaultModel,
            groqApiKey = rawGroqKey,
            groqModel = rawGroqModel,
            openRouterApiKey = prefs[Keys.OPENROUTER_KEY].orEmpty(),
            openRouterModel = prefs[Keys.OPENROUTER_MODEL]
                ?.takeIf { it.isNotBlank() } ?: AiProvider.OPENROUTER.defaultModel,
            autoCrossProviderFailover = prefs[Keys.AUTO_CROSS_PROVIDER_FAILOVER] ?: true,
            routingStrategy = routingStrategy,
            smartTextFastPath = prefs[Keys.SMART_TEXT_FAST_PATH] ?: true,
            turboDualKeyGameMode = prefs[Keys.TURBO_DUAL_KEY_GAME_MODE] ?: true,
            useZeroApiActionCache = prefs[Keys.USE_ZERO_API_ACTION_CACHE] ?: true,
            carromPrecisionSolver = prefs[Keys.CARROM_PRECISION_SOLVER] ?: true,
            carromRuleMode = carromRuleMode,
            usePhysicsRollout = prefs[Keys.USE_PHYSICS_ROLLOUT] ?: true,
            useSubPixelCircleRefinement = prefs[Keys.USE_SUBPIXEL_CIRCLE_REFINEMENT] ?: true,
            useClosedLoopSliderVerify = prefs[Keys.USE_CLOSED_LOOP_SLIDER_VERIFY] ?: true,
            useCoordinateGridOverlay = prefs[Keys.USE_COORDINATE_GRID_OVERLAY] ?: true,
            useSetOfMarkOverlay = prefs[Keys.USE_SET_OF_MARK_OVERLAY] ?: true,
            preventDoubleText = prefs[Keys.PREVENT_DOUBLE_TEXT] ?: true,
            autoReplySavedContactsOnly = prefs[Keys.AUTO_REPLY_SAVED_CONTACTS_ONLY] ?: false,
            replyOnlyOnMessageNotifications = prefs[Keys.REPLY_ONLY_ON_NOTIFICATIONS] ?: true,
            maxSteps = (prefs[Keys.MAX_STEPS] ?: 20).coerceIn(5, 50),
            monitorIntervalSec = (prefs[Keys.MONITOR_INTERVAL_SEC] ?: 30).coerceIn(10, 300),
            useVisionScreenshot = prefs[Keys.USE_VISION] ?: true,
            autoResearchBeforeTask = prefs[Keys.AUTO_RESEARCH] ?: true,
            safetyPolicyMode = safetyMode,
            goHomeOnLaunch = prefs[Keys.GO_HOME_ON_LAUNCH] ?: true,
            keepScreenAwake = prefs[Keys.KEEP_SCREEN_AWAKE] ?: false,
            showFloatingOverlay = prefs[Keys.SHOW_FLOATING_OVERLAY] ?: false,
            stepDelayMs = (prefs[Keys.STEP_DELAY_MS] ?: 1200L).coerceIn(400L, 4000L)
        )
    }
}
