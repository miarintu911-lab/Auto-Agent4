package com.example.agent

import android.graphics.Bitmap

data class FreeModelOption(
    val id: String,
    val label: String,
    val supportsVision: Boolean
)

enum class AiProvider(
    val displayName: String,
    val shortBadge: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val freeModels: List<FreeModelOption>
) {
    GOOGLE_AI_STUDIO(
        displayName = "Google AI Studio",
        shortBadge = "AI Studio Free",
        defaultModel = "gemini-3.1-flash-lite-preview",
        requiresApiKey = true,
        freeModels = listOf(
            FreeModelOption("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash-Lite (Fast Vision)", true),
            FreeModelOption("gemini-2.5-flash", "Gemini 2.5 Flash (Vision)", true),
            FreeModelOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite (High Quota)", true),
            FreeModelOption("gemini-2.0-flash", "Gemini 2.0 Flash (Vision)", true)
        )
    ),
    GROQ_CLOUD(
        displayName = "Groq Cloud",
        shortBadge = "Groq Free",
        defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        requiresApiKey = true,
        freeModels = listOf(
            FreeModelOption("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B (Vision)", true),
            FreeModelOption("meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick 17B (Vision)", true),
            FreeModelOption("llama-3.3-70b-versatile", "Llama 3.3 70B (UI Tree)", false),
            FreeModelOption("qwen/qwen3-32b", "Qwen 3 32B (UI Tree)", false)
        )
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortBadge = "OpenRouter Free",
        defaultModel = "openrouter/free",
        requiresApiKey = true,
        freeModels = listOf(
            FreeModelOption("openrouter/free", "Free Auto-Router (Smart Vision)", true),
            FreeModelOption("nvidia/nemotron-3.5-super-vl:free", "Nemotron 3.5 Super VL (Vision)", true),
            FreeModelOption("google/gemma-4-31b-it:free", "Gemma 4 31B IT (Vision)", true),
            FreeModelOption("google/gemma-4-26b-a4b-it:free", "Gemma 4 26B A4B (Vision)", true),
            FreeModelOption("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra 550B (UI Tree)", false)
        )
    ),
    FIREBASE_AI(
        displayName = "Firebase AI (Built-in)",
        shortBadge = "Firebase Gemini",
        defaultModel = "gemini-3.8-flash",
        requiresApiKey = false,
        freeModels = listOf(
            FreeModelOption("gemini-3.8-flash", "Gemini 3.8 Flash (Built-in)", true)
        )
    )
}

enum class AgentExecutionMode {
    SINGLE_SESSION,
    CONTINUOUS_MONITOR
}

enum class SafetyPolicyMode {
    PAYMENT_ONLY,
    STRICT_ALL
}

enum class AiRoutingStrategy(val label: String) {
    SMART_HYBRID("Smart Round-Robin (Fast + Zero 1m Limit)"),
    STICKY_FAILOVER("Sticky Primary + Failover")
}

enum class CarromRuleMode(val label: String) {
    FREESTYLE("Freestyle (All Coins)"),
    WHITE_ONLY("Disc Pool: White"),
    BLACK_ONLY("Disc Pool: Black")
}

enum class AgentActionType {
    TAP,
    DRAG,
    LONG_PRESS,
    INPUT_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    BACK,
    HOME,
    WAIT,
    COMPLETE,
    FAIL;

    companion object {
        fun fromWire(value: String): AgentActionType {
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: FAIL
        }
    }
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toCompactString(): String = "[$left,$top,$right,$bottom]"
}

data class ScreenNode(
    val id: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val bounds: ScreenBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean
) {
    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDesc.isNotBlank() -> contentDesc
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }
    }
}

data class UiSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNode>,
    val screenshot: Bitmap?,
    val captureNote: String
)

data class NotificationSignal(
    val packageName: String,
    val text: String,
    val timestampMs: Long,
    val senderName: String = "",
    val messageBody: String = ""
)

data class PlannedAction(
    val actionType: AgentActionType,
    val targetNodeId: Int?,
    val x: Int?,
    val y: Int?,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Long? = null,
    val textToType: String?,
    val waitSeconds: Int?,
    val memoryNote: String?,
    val learnedRule: String? = null,
    val foulOrMistakeLesson: String? = null,
    val reason: String,
    val requiresConfirmation: Boolean
)

data class TaskPlaybook(
    val topicKey: String,
    val topicTitle: String,
    val rulesSummary: String,
    val winningStrategies: List<String>,
    val foulLessons: List<String>,
    val trainingSessions: Int = 1,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    fun formatForPrompt(): String {
        val strategiesText = if (winningStrategies.isEmpty()) {
            "- None recorded yet."
        } else {
            winningStrategies.joinToString("\n") { "- $it" }
        }
        val foulsText = if (foulLessons.isEmpty()) {
            "- None recorded yet."
        } else {
            foulLessons.joinToString("\n") { "- AVOID: $it" }
        }
        return """
            PLAYBOOK TOPIC: $topicTitle (Trained $trainingSessions session(s))
            CORE RULES & CONTROLS:
            $rulesSummary
            WINNING / POCKET / EXECUTION STRATEGIES:
            $strategiesText
            FOULS & MISTAKES TO AVOID:
            $foulsText
        """.trimIndent()
    }
}

enum class ChatPlatform(val label: String) {
    WHATSAPP("WhatsApp"),
    WHATSAPP_BUSINESS("WA Business"),
    FACEBOOK_MESSENGER("Facebook / Messenger"),
    BOTH("WhatsApp + Facebook"),
    OTHER("Other App");

    fun matchesPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        val isWaBusiness = pkg == "com.whatsapp.w4b" || pkg.contains("w4b") || pkg.contains("whatsapp.business")
        val isWaPersonal = pkg == "com.whatsapp" || (pkg.contains("whatsapp") && !isWaBusiness)
        val isWaAny = isWaPersonal || isWaBusiness
        val isFb = pkg.contains("facebook") || pkg.contains("orca") || pkg.contains("mlite")
        return when (this) {
            WHATSAPP -> isWaPersonal
            WHATSAPP_BUSINESS -> isWaBusiness
            FACEBOOK_MESSENGER -> isFb
            BOTH -> isWaAny || isFb || pkg.isBlank()
            OTHER -> true
        }
    }
}

enum class RelationshipType(val label: String, val toneGuidance: String) {
    FRIEND(
        label = "বন্ধু / Friend",
        toneGuidance = "Chat warmly like a close friend in natural casual Bengali/Banglish ('দোস্ত', 'মামা', 'ব্রো'), friendly humor, relaxed tone, never robotic."
    ),
    GF(
        label = "জিএফ / GF",
        toneGuidance = "Chat affectionately and lovingly as a caring partner, warm sweet tone, attentive caring questions, gentle warmth and emojis."
    ),
    BROTHER(
        label = "ভাই / Brother",
        toneGuidance = "Chat with genuine brotherly warmth and family respect ('ভাইয়া' / 'ভাই'), supportive and natural."
    ),
    SISTER(
        label = "বোন / Sister",
        toneGuidance = "Chat with caring brotherly/sisterly warmth and respect ('আপু' / 'বোন'), protective, polite, friendly."
    ),
    FAMILY(
        label = "পরিবার / Family",
        toneGuidance = "Chat politely and respectfully like talking to family/elders ('জি', respectful greetings, asking about family)."
    ),
    BUSINESS(
        label = "ক্লায়েন্ট / Business",
        toneGuidance = "Chat politely, clearly, and professionally focusing on pricing, availability, and order details."
    )
}

data class ContactProfile(
    val id: String,
    val name: String,
    val platform: ChatPlatform,
    val relationship: RelationshipType,
    val customStyleNote: String = "",
    val autoReplyEnabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun formatForPrompt(): String {
        val noteLine = if (customStyleNote.isNotBlank()) {
            "\n- Custom Note: $customStyleNote"
        } else {
            ""
        }
        return """
            CONTACT PROFILE MATCHED: $name
            - Platform: ${platform.label}
            - Relationship: ${relationship.label}
            - Tone & Behavior Rule: ${relationship.toneGuidance}$noteLine
        """.trimIndent()
    }
}

data class CachedActionEntry(
    val cacheKey: String,
    val goalKeywords: String,
    val packageName: String,
    val screenSignature: String,
    val actionType: AgentActionType,
    val targetLabel: String,
    val targetViewId: String,
    val tapX: Int?,
    val tapY: Int?,
    val textToType: String?,
    val hitCount: Int = 1,
    val lastUsedMs: Long = System.currentTimeMillis()
)

enum class StepStatus {
    EXECUTED,
    WAITING,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}

data class AgentStepLog(
    val stepNumber: Int,
    val timestampMs: Long,
    val actionType: AgentActionType,
    val targetSummary: String,
    val reason: String,
    val status: StepStatus,
    val requiresConfirmation: Boolean
)

enum class AgentPhase(val label: String) {
    RESEARCHING("Researching rules, controls & strategy..."),
    OBSERVING("Observing screen & UI tree..."),
    THINKING("AI reasoning next action..."),
    ACTING("Dispatching accessibility gesture..."),
    WAITING_DELAY("Waiting for UI transition..."),
    MONITOR_SLEEPING("Monitoring in background (wakes on notification)...")
}

sealed interface AgentUiState {
    data object Idle : AgentUiState

    data class Running(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val phase: AgentPhase,
        val lastThought: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Paused(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val pauseReason: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class AwaitingConfirmation(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val pendingAction: PlannedAction,
        val targetDescription: String,
        val safetyReason: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Completed(
        val goal: String,
        val summary: String,
        val totalSteps: Int,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Error(
        val goal: String,
        val message: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState
}

data class AgentSettings(
    val aiProvider: AiProvider = AiProvider.GOOGLE_AI_STUDIO,
    val googleAiStudioApiKey: String = "",
    val googleAiStudioModel: String = AiProvider.GOOGLE_AI_STUDIO.defaultModel,
    val groqApiKey: String = "",
    val groqModel: String = AiProvider.GROQ_CLOUD.defaultModel,
    val openRouterApiKey: String = "",
    val openRouterModel: String = AiProvider.OPENROUTER.defaultModel,
    val autoCrossProviderFailover: Boolean = true,
    val routingStrategy: AiRoutingStrategy = AiRoutingStrategy.SMART_HYBRID,
    val smartTextFastPath: Boolean = true,
    val turboDualKeyGameMode: Boolean = true,
    val useZeroApiActionCache: Boolean = true,
    val carromPrecisionSolver: Boolean = true,
    val carromRuleMode: CarromRuleMode = CarromRuleMode.FREESTYLE,
    val usePhysicsRollout: Boolean = true,
    val useSubPixelCircleRefinement: Boolean = true,
    val useClosedLoopSliderVerify: Boolean = true,
    val useCoordinateGridOverlay: Boolean = true,
    val preventDoubleText: Boolean = true,
    val autoReplySavedContactsOnly: Boolean = false,
    val replyOnlyOnMessageNotifications: Boolean = true,
    val maxSteps: Int = 20,
    val monitorIntervalSec: Int = 30,
    val useVisionScreenshot: Boolean = true,
    val autoResearchBeforeTask: Boolean = true,
    val safetyPolicyMode: SafetyPolicyMode = SafetyPolicyMode.PAYMENT_ONLY,
    val goHomeOnLaunch: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val stepDelayMs: Long = 1200L
) {
    fun rawKeyBlockFor(provider: AiProvider): String = when (provider) {
        AiProvider.FIREBASE_AI -> ""
        AiProvider.GOOGLE_AI_STUDIO -> googleAiStudioApiKey
        AiProvider.GROQ_CLOUD -> groqApiKey
        AiProvider.OPENROUTER -> openRouterApiKey
    }

    fun apiKeysFor(provider: AiProvider): List<String> = parseApiKeys(rawKeyBlockFor(provider))

    fun activeApiKey(): String = apiKeysFor(aiProvider).firstOrNull().orEmpty()

    fun totalConfiguredKeyCount(): Int {
        return apiKeysFor(AiProvider.GOOGLE_AI_STUDIO).size +
            apiKeysFor(AiProvider.GROQ_CLOUD).size +
            apiKeysFor(AiProvider.OPENROUTER).size
    }

    fun hasAnyRunnableKey(firebaseConfigured: Boolean): Boolean {
        if (!aiProvider.requiresApiKey && firebaseConfigured) return true
        if (apiKeysFor(aiProvider).isNotEmpty()) return true
        if (!autoCrossProviderFailover) return false
        return apiKeysFor(AiProvider.GOOGLE_AI_STUDIO).isNotEmpty() ||
            apiKeysFor(AiProvider.GROQ_CLOUD).isNotEmpty() ||
            apiKeysFor(AiProvider.OPENROUTER).isNotEmpty() ||
            firebaseConfigured
    }

    fun modelNameFor(provider: AiProvider): String = when (provider) {
        AiProvider.FIREBASE_AI -> AiProvider.FIREBASE_AI.defaultModel
        AiProvider.GOOGLE_AI_STUDIO -> googleAiStudioModel.trim().ifBlank { AiProvider.GOOGLE_AI_STUDIO.defaultModel }
        AiProvider.GROQ_CLOUD -> groqModel.trim().ifBlank { AiProvider.GROQ_CLOUD.defaultModel }
        AiProvider.OPENROUTER -> openRouterModel.trim().ifBlank { AiProvider.OPENROUTER.defaultModel }
    }

    fun activeModelName(): String = modelNameFor(aiProvider)

    companion object {
        fun parseApiKeys(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val seen = LinkedHashSet<String>()
            raw.split(Regex("[\\s,;]+"))
                .map { it.trim() }
                .filter { it.length >= 8 }
                .forEach { seen.add(it) }
            return seen.toList()
        }
    }
}

object CarromContextHelper {
    fun isCarromGoalOrApp(
        goal: String,
        packageName: String,
        playbookTopicKey: String?
    ): Boolean {
        return goal.contains("carrom", ignoreCase = true) ||
            goal.contains("ক্যারাম", ignoreCase = true) ||
            goal.contains("ক্যারম", ignoreCase = true) ||
            goal.contains("কেরাম", ignoreCase = true) ||
            goal.contains("কারাম", ignoreCase = true) ||
            goal.contains("caram", ignoreCase = true) ||
            goal.contains("disc pool", ignoreCase = true) ||
            packageName.contains("carrom", ignoreCase = true) ||
            packageName.contains("caram", ignoreCase = true) ||
            (playbookTopicKey?.contains("carrom", ignoreCase = true) == true)
    }

    fun isLikelyGameLobbyMenu(nodes: List<ScreenNode>): Boolean {
        val lobbyLabels = setOf(
            "play", "play classic", "disc pool", "freestyle", "carrom",
            "shop", "store", "tournament", "matchmaking", "find match",
            "claim", "collect", "login", "sign in", "continue", "খেলুন", "খেলা শুরু"
        )
        return nodes.any { node ->
            if (!node.isClickable) return@any false
            val label = node.displayLabel().trim().lowercase()
            label.isNotBlank() && (
                label in lobbyLabels ||
                    label.startsWith("play ") ||
                    label.contains("classic") ||
                    label.contains("disc pool") ||
                    label.contains("freestyle")
                )
        }
    }

    fun buildDefaultCarromPlaybook(ruleMode: CarromRuleMode = CarromRuleMode.FREESTYLE): TaskPlaybook {
        val isFreestyle = ruleMode == CarromRuleMode.FREESTYLE
        val ruleDesc = if (isFreestyle) {
            "Freestyle Carrom: White, Black, and Red (Queen) coins are all legal targets. Only striker pocket scratch is a foul. Cover Red (Queen) when needed. Move bottom slider knob horizontally to align striker, then slingshot-drag striker center opposite pocket direction."
        } else {
            "Disc Pool Carrom (${ruleMode.label}): Pocket only your assigned color coins + Red (Queen). Pocketing only opponent coins or scratching striker into a pocket is a foul."
        }
        return TaskPlaybook(
            topicKey = "carrom_board_game",
            topicTitle = if (isFreestyle) "Carrom Freestyle" else "Carrom Disc Pool",
            rulesSummary = ruleDesc,
            winningStrategies = listOf(
                "Pre-scan board and rank top 6 baseline striker X spots before sliding.",
                "Slide bottom slider knob to probe candidate spots (up to 6 moves) until physics verifies a Sure Shot.",
                "Accept collision-assisted carom shots when they still pocket a valid coin cleanly with zero striker scratch.",
                "Pull slingshot opposite to pocket vector from striker center; shoot safest zero-foul break/pot before 7.0s cutoff."
            ),
            foulLessons = listOf(
                "Striker falling into any pocket is a foul — reject straight scratch trajectories via 2D physics rollout.",
                "Reject colliding lines that deflect target away from pocket or cause striker scratch."
            ),
            trainingSessions = 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }
}
