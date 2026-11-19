package com.example.ai

import android.util.Log
import com.example.agent.AgentActionType
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentKnowledgeStore
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AiProvider
import com.example.agent.ContactProfile
import com.example.agent.NotificationSignal
import com.example.agent.PlannedAction
import com.example.agent.TaskPlaybook
import com.example.agent.UiSnapshot
import com.example.service.NodeTreeParser
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GeminiAiPlanner"
private const val INFERENCE_TIMEOUT_MS = 60_000L

class GeminiAiPlanner {

    private val systemPromptText = """
        You are TapPilot, an adaptive Android native AccessibilityService automation & self-training agent.
        You observe the Android screen via a structured UI Node Tree, a visual Screenshot image, incoming notifications, your session workingMemory, and your persistent Playbook (researched rules + winning strategies + fouls/mistakes to avoid).
        Your job is to research/understand unfamiliar games or apps, execute the optimal next gesture, and continuously learn from the visual outcome of every previous action!

        Gesture Rules:
        1. TAP: Use targetNodeId + (x, y) center for standard UI buttons, or visual (x, y) on game canvases/boards where no UI node exists. If the screenshot includes cyan X/Y pixel grid labels (e.g. x216, y600), read coordinates directly from those grid lines for sub-pixel precision.
        2. DRAG: Use (x, y) as start point and (endX, endY) as release point with optional durationMs (e.g. 250..1200ms).
           - For slider controls: drag the separate slider knob below the board horizontally to position the striker. NEVER drag the striker itself sideways on the baseline to reposition it, because touching & dragging the striker immediately aims and fires a shot!
           - For slingshot / Carrom / Pool / Golf aiming: start (x, y) at the exact center of the striker/ball and drag OPPOSITE to the desired shot direction by 160..340px to (endX, endY) with durationMs ~550ms, then release.
           - CARROM TURN RULE (CRITICAL): Whenever a striker of ANY color or custom skin (white, black, cyan, gold, red, brown/monster/dragon, patterned, or glowing ring) is sitting on the BOTTOM baseline (y ~ 58%-74%) OR a bottom slider knob below the board is visible, IT IS YOUR TURN — ALWAYS execute a DRAG shot (or slider move) immediately! NEVER return WAIT when any striker is on the bottom side. On opening break racks, pull back ~280..360px opposite the center cluster to smash cleanly; in Points/Freestyle mode (Red=50, White=20, Black=10), target Red/White first. Only WAIT if the striker is at the TOP baseline (opponent's turn).
        3. LONG_PRESS: Use (x, y) with durationMs (e.g. 700..1500ms) for hold-to-charge shots or context menus.
        4. INPUT_TEXT: Only on editable fields, with textToType.
        5. SCROLL_DOWN / SCROLL_UP / SWIPE_LEFT / SWIPE_RIGHT / HOME / BACK:
           - Use SCROLL_DOWN / SCROLL_UP to scroll vertically.
           - Use SWIPE_LEFT / SWIPE_RIGHT to slide horizontally left or right across tabs, pages, carousels, or stories.
           - APP DRAWER SEARCH RULE (MANDATORY): Whenever the user asks to do any work inside an app and you are NOT already in that app, FIRST open the App Drawer / Apps screen (e.g. Samsung One UI App Drawer with the bottom 'Search' bar — swipe up via DRAG if you are on the Home screen), TAP the 'Search' bar, type the EXACT app name via INPUT_TEXT, and TAP the matching search result to enter the app before performing the task inside it!
           - WRONG-PAGE / WRONG-SLIDE RECOVERY: If a tap, scroll, or left/right slide accidentally opens the wrong page or wrong app, IMMEDIATELY press BACK (and note the mistake in foulOrMistakeLesson) so TapPilot purges that wrong action from cache and corrects course.

        Self-Training & Mistake Learning Rules:
        6. Carefully compare the PREVIOUS ACTION and its coordinates against the CURRENT SCREENSHOT:
           - If your previous shot/action worked well (e.g. pocketed a coin, aligned well, advanced goal), record a concise reusable takeaway in learnedRule mentioning: WHICH pocket/target, soft vs medium vs hard power, and whether sliding the striker or shooting in place worked.
           - If your previous action caused a foul, missed pocket, bounced out, hit opponent piece, or failed (e.g. striker went into pocket = foul, shot too weak/strong), record a concise lesson in foulOrMistakeLesson with exact fix (e.g. "Top-Right straight shot scratched striker -> soften pull & widen angle").
        7. Keep workingMemory updated via memoryNote with live board/task state (e.g. "My color: white, Queen covered, avoid top-left straight scratch").

        Safety & Chat Turn-Taking Rules:
        8. Normal app tasks, social posting/chatting, and gameplay actions MUST have requiresConfirmation = false so they run smoothly.
        9. Set requiresConfirmation = true ONLY for money payments, financial transfers, final checkout/place-order confirmation, card/PIN/OTP entry, or factory reset.
        10. In CONTINUOUS_MONITOR mode, return WAIT with waitSeconds (e.g. 15..90) when idling between notifications or checks.
        11. ANTI-DOUBLE-TEXT / NO-SPAM CHAT RULE (CRITICAL): In Messenger, Facebook, WhatsApp, Instagram, or Telegram, NEVER send another message (`INPUT_TEXT` or `Send`) if your previous message is still the latest bubble or the recipient has NOT replied yet! Do NOT send repeated follow-ups like "কি খবর বন্ধু" or "hello?". Instead, return `WAIT` (`waitSeconds = 25..45`) until an incoming reply or new notification arrives.
        12. NOTIFICATION-ONLY AUTO-REPLY & RELATIONSHIP PERSONA RULE:
           - When `ACTIVE NOTIFICATION TARGET LOCK` is shown in `LIVE CHAT & TURN-TAKING STATUS`, you MUST open ONLY that exact sender ID inside that exact app — NEVER open other random chats.
           - ALWAYS check `MATCHED CONTACT & RELATIONSHIP PERSONA` and `SAVED CONTACTS ROSTER` (WhatsApp / WA Business / Facebook / Messenger) FIRST:
             * If a relationship IS saved for that sender + app (Friend/বন্ধু, GF/জিএফ, Brother/ভাই, Sister/বোন, Family/পরিবার, Business), strictly adopt that saved relationship tone, honorifics, and custom note.
             * If NO relationship is saved (`NO MATCH` and Saved-Contacts-Only is off), speak warmly, politely, respectfully, and nicely (সুন্দর, মিষ্টি ও ভদ্রভাবে কথা বলো) according to what they wrote!
        13. STRONGER ACTION RECOVERY: Never repeat the exact same failing TAP/DRAG. If `PREVIOUS ACTION RESULT` warns `[NO SCREEN CHANGE]`, immediately change strategy: pick the parent container/text node, use visual coordinates from the screenshot, scroll slightly, slide left/right, or press `BACK`.
    """.trimIndent()

    private val researchSystemPrompt = """
        You are TapPilot's Pre-Task Research & Training Strategist.
        Before the agent opens an unfamiliar game, app, or workflow on Android, research and synthesize an actionable Playbook.
        Include:
        1. Exact rules, scoring/win conditions, and touch controls (taps, drags, slingshot pull-back aiming opposite the pocket direction, striker slider placement, etc.).
        2. Practical winning/pocketing/execution techniques (e.g. straight-line pocket alignment, cut shots, cover-queen rules in Carrom, or navigation flow for apps).
        3. Specific fouls, penalties, and failure traps to avoid (e.g. striker pocketing = foul + return coin; touching opponent coin directly; double-tap or invalid checkout taps).
        Keep every point crisp, concrete, and directly usable during live Android screen automation.
    """.trimIndent()

    private val actionSchema = Schema.obj(
        properties = mapOf(
            "actionType" to Schema.enumeration(
                values = listOf(
                    "TAP",
                    "DRAG",
                    "LONG_PRESS",
                    "INPUT_TEXT",
                    "SCROLL_DOWN",
                    "SCROLL_UP",
                    "SWIPE_LEFT",
                    "SWIPE_RIGHT",
                    "BACK",
                    "HOME",
                    "WAIT",
                    "COMPLETE",
                    "FAIL"
                ),
                description = "Next automation gesture or lifecycle decision."
            ),
            "targetNodeId" to Schema.integer(
                description = "Numeric node id from UI Node Tree when targeting a clickable/editable UI node.",
                nullable = true
            ),
            "x" to Schema.integer(
                description = "Horizontal screen pixel coordinate (start X for DRAG, tap X for TAP/LONG_PRESS).",
                nullable = true
            ),
            "y" to Schema.integer(
                description = "Vertical screen pixel coordinate (start Y for DRAG, tap Y for TAP/LONG_PRESS).",
                nullable = true
            ),
            "endX" to Schema.integer(
                description = "Ending horizontal screen pixel coordinate when actionType is DRAG.",
                nullable = true
            ),
            "endY" to Schema.integer(
                description = "Ending vertical screen pixel coordinate when actionType is DRAG.",
                nullable = true
            ),
            "durationMs" to Schema.integer(
                description = "Gesture duration in milliseconds for DRAG (150..2000) or LONG_PRESS (500..2500).",
                nullable = true
            ),
            "textToType" to Schema.string(
                description = "Text payload when actionType is INPUT_TEXT.",
                nullable = true
            ),
            "waitSeconds" to Schema.integer(
                description = "Seconds to wait before next check when actionType is WAIT.",
                nullable = true
            ),
            "memoryNote" to Schema.string(
                description = "Updated short-term working memory of current progress/match state.",
                nullable = true
            ),
            "learnedRule" to Schema.string(
                description = "New winning strategy or effective shot/control technique discovered from the previous step's result.",
                nullable = true
            ),
            "foulOrMistakeLesson" to Schema.string(
                description = "New foul rule, miss reason, or mistake observed after the previous action that should be avoided forever.",
                nullable = true
            ),
            "reason" to Schema.string(
                description = "Concise explanation of why this action advances the goal."
            ),
            "requiresConfirmation" to Schema.boolean(
                description = "True ONLY for payments, transfers, final checkout, card/PIN/OTP, or factory reset."
            )
        ),
        optionalProperties = listOf(
            "targetNodeId",
            "x",
            "y",
            "endX",
            "endY",
            "durationMs",
            "textToType",
            "waitSeconds",
            "memoryNote",
            "learnedRule",
            "foulOrMistakeLesson"
        )
    )

    private val researchSchema = Schema.obj(
        properties = mapOf(
            "topicKey" to Schema.string(description = "Short snake_case key e.g. carrom_board_game or facebook_marketplace"),
            "topicTitle" to Schema.string(description = "Readable title of the game, app, or skill."),
            "rulesSummary" to Schema.string(description = "Concise summary of rules, controls, and objective."),
            "winningStrategies" to Schema.array(
                items = Schema.string(),
                description = "3 to 5 concrete winning/pocketing/execution techniques."
            ),
            "foulLessons" to Schema.array(
                items = Schema.string(),
                description = "3 to 5 concrete foul rules, penalties, or common mistakes to avoid."
            )
        )
    )

    private val config = generationConfig {
        responseMimeType = "application/json"
        responseSchema = actionSchema
        temperature = 0.2f
    }

    private val researchConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = researchSchema
        temperature = 0.25f
    }

    private val firebaseModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config,
            systemInstruction = content { text(systemPromptText) }
        )
    }

    private val firebaseResearchModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = researchConfig,
            systemInstruction = content { text(researchSystemPrompt) }
        )
    }

    suspend fun researchTaskPlaybook(
        goal: String,
        settings: AgentSettings
    ): Result<TaskPlaybook> {
        return try {
            val promptText = """
                USER TASK / GAME GOAL: $goal

                Research this game, app, or workflow thoroughly before the agent touches the screen.
                Return:
                - topicKey: concise snake_case identifier
                - topicTitle: clear name
                - rulesSummary: how the game/app works, touch gestures needed (TAP vs DRAG aim-and-release vs slider), and scoring/win condition
                - winningStrategies: 3-5 practical strategies (e.g. for Carrom: how to position striker on baseline, aim straight through center of target coin toward pocket, pull opposite to target vector, cover Queen immediately)
                - foulLessons: 3-5 exact fouls/mistakes to avoid (e.g. for Carrom: pocketing striker = foul & return coin; hitting opponent piece directly before yours; pocketing opponent piece accidentally)
            """.trimIndent()

            val attempt = withTimeout(12_000L) {
                ApiKeyFailoverManager.executeWithFailover(
                    settings = settings,
                    firebaseConfigured = isFirebaseConfigured(),
                    taskProfile = AiTaskProfile.HIGH_ACCURACY
                ) { provider, apiKey, model ->
                    val raw = if (provider == AiProvider.FIREBASE_AI) {
                        firebaseResearchModel.generateContent(promptText).text.orEmpty()
                    } else {
                        RestAiClients.callProvider(
                            provider = provider,
                            apiKey = apiKey,
                            modelName = model,
                            systemInstruction = researchSystemPrompt,
                            promptText = promptText,
                            screenshot = null,
                            schemaKind = RestSchemaKind.RESEARCH
                        )
                    }
                    if (raw.isBlank()) throw IllegalStateException("${provider.displayName} returned empty research response.")
                    parseResearchPlaybook(raw, goal)
                }
            }

            Result.success(attempt.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pre-task research failed", e)
            Result.failure(e)
        }
    }

    suspend fun planNextAction(
        goal: String,
        mode: AgentExecutionMode,
        settings: AgentSettings,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        recentNotifications: List<NotificationSignal>,
        workingMemory: String,
        playbook: TaskPlaybook?,
        lastOutcomeHint: String,
        stepNumber: Int,
        maxSteps: Int?,
        matchedContact: ContactProfile? = null,
        savedContactsSummary: String = "",
        chatGuardHint: String = "",
        carromMemorySummary: String = "",
        forceVision: Boolean = false
    ): Result<PlannedAction> {
        return try {
            val promptText = buildPrompt(
                goal = goal,
                mode = mode,
                snapshot = snapshot,
                recentHistory = recentHistory,
                recentNotifications = recentNotifications,
                workingMemory = workingMemory,
                playbook = playbook,
                lastOutcomeHint = lastOutcomeHint,
                stepNumber = stepNumber,
                maxSteps = maxSteps,
                matchedContact = matchedContact,
                savedContactsSummary = savedContactsSummary,
                chatGuardHint = chatGuardHint,
                carromMemorySummary = carromMemorySummary
            )

            val (taskProfile, attachScreenshot) = resolveVisionStrategy(
                settings = settings,
                interactiveNodes = snapshot.nodes.count { it.isClickable || it.isEditable },
                lastOutcomeHint = lastOutcomeHint,
                stepNumber = stepNumber,
                forceVision = forceVision
            )
            val effectiveScreenshot = if (attachScreenshot) snapshot.screenshot else null

            val annotatedCopy = if (
                effectiveScreenshot != null &&
                settings.useCoordinateGridOverlay &&
                taskProfile == AiTaskProfile.HIGH_ACCURACY
            ) {
                VisualGridAnnotator.createAnnotatedCopy(
                    source = effectiveScreenshot,
                    screenWidth = snapshot.screenWidth,
                    screenHeight = snapshot.screenHeight
                )
            } else {
                null
            }
            val aiScreenshot = annotatedCopy ?: effectiveScreenshot

            val attempt = try {
                withTimeout(INFERENCE_TIMEOUT_MS * 2) {
                    ApiKeyFailoverManager.executeWithHedgeOrFailover(
                        settings = settings,
                        firebaseConfigured = isFirebaseConfigured(),
                        taskProfile = taskProfile,
                        enableHedgeRace = settings.turboDualKeyGameMode && taskProfile == AiTaskProfile.HIGH_ACCURACY
                    ) { provider, apiKey, model ->
                        val raw = if (provider == AiProvider.FIREBASE_AI) {
                            firebaseModel.generateContent(
                                content {
                                    aiScreenshot?.let { bitmap ->
                                        image(bitmap)
                                    }
                                    text(promptText)
                                }
                            ).text.orEmpty()
                        } else {
                            RestAiClients.callProvider(
                                provider = provider,
                                apiKey = apiKey,
                                modelName = model,
                                systemInstruction = systemPromptText,
                                promptText = promptText,
                                screenshot = aiScreenshot,
                                schemaKind = RestSchemaKind.ACTION
                            )
                        }
                        if (raw.isBlank()) throw IllegalStateException("${provider.displayName} returned empty action response.")
                        parsePlannedAction(raw)
                    }
                }
            } finally {
                if (annotatedCopy != null && annotatedCopy !== effectiveScreenshot) {
                    annotatedCopy.recycle()
                }
            }

            Result.success(attempt.value)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI planning timed out", e)
            Result.failure(IllegalStateException("AI reasoning timed out across failover keys."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI planning failed", e)
            Result.failure(e)
        }
    }

    fun isFirebaseConfigured(): Boolean {
        return runCatching {
            com.google.firebase.FirebaseApp.getInstance()
        }.isSuccess
    }

    internal fun parseResearchPlaybook(jsonString: String, fallbackGoal: String): TaskPlaybook {
        val clean = RestAiClients.extractJsonObjectString(jsonString)
        val json = JSONObject(clean)
        val rawTitle = json.optString("topicTitle").trim().ifBlank {
            fallbackGoal.take(44).ifBlank { "Task Playbook" }
        }
        val rawKey = json.optString("topicKey").trim().ifBlank { rawTitle }
        val rules = json.optString("rulesSummary").trim()
        val strategies = jsonArrayToStrings(json.optJSONArray("winningStrategies"))
        val fouls = jsonArrayToStrings(json.optJSONArray("foulLessons"))

        return TaskPlaybook(
            topicKey = AgentKnowledgeStore.normalizeKey(rawKey),
            topicTitle = rawTitle,
            rulesSummary = rules,
            winningStrategies = strategies,
            foulLessons = fouls,
            trainingSessions = 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    internal fun parsePlannedAction(jsonString: String): PlannedAction {
        val json = JSONObject(RestAiClients.extractJsonObjectString(jsonString))
        val actionType = AgentActionType.fromWire(json.getString("actionType"))
        val targetNodeId = optPositiveInt(json, "targetNodeId")
        val x = optNonNegativeInt(json, "x")
        val y = optNonNegativeInt(json, "y")
        val endX = optNonNegativeInt(json, "endX")
        val endY = optNonNegativeInt(json, "endY")
        val durationMs = if (json.has("durationMs") && !json.isNull("durationMs")) {
            json.optLong("durationMs", -1L).takeIf { it > 0L }?.coerceIn(120L, 3000L)
        } else {
            null
        }
        val textToType = optCleanString(json, "textToType")
        val waitSeconds = if (json.has("waitSeconds") && !json.isNull("waitSeconds")) {
            json.optInt("waitSeconds", -1).takeIf { it > 0 }?.coerceIn(3, 300)
        } else {
            null
        }
        val memoryNote = optCleanString(json, "memoryNote")
        val learnedRule = optCleanString(json, "learnedRule")
        val foulOrMistakeLesson = optCleanString(json, "foulOrMistakeLesson")
        val reason = json.optString("reason", "Proceeding with goal.")
        val requiresConfirmation = json.optBoolean("requiresConfirmation", false)

        return PlannedAction(
            actionType = actionType,
            targetNodeId = targetNodeId,
            x = x,
            y = y,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            textToType = textToType,
            waitSeconds = waitSeconds,
            memoryNote = memoryNote,
            learnedRule = learnedRule,
            foulOrMistakeLesson = foulOrMistakeLesson,
            reason = reason,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun optPositiveInt(json: JSONObject, key: String): Int? {
        return if (json.has(key) && !json.isNull(key)) {
            json.optInt(key, -1).takeIf { it > 0 }
        } else {
            null
        }
    }

    private fun optNonNegativeInt(json: JSONObject, key: String): Int? {
        return if (json.has(key) && !json.isNull(key)) {
            json.optInt(key, -1).takeIf { it >= 0 }
        } else {
            null
        }
    }

    private fun optCleanString(json: JSONObject, key: String): String? {
        return if (json.has(key) && !json.isNull(key)) {
            json.optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        } else {
            null
        }
    }

    private fun jsonArrayToStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optString(i).trim()
            if (item.isNotBlank()) out.add(item)
        }
        return out
    }

    private fun buildPrompt(
        goal: String,
        mode: AgentExecutionMode,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        recentNotifications: List<NotificationSignal>,
        workingMemory: String,
        playbook: TaskPlaybook?,
        lastOutcomeHint: String,
        stepNumber: Int,
        maxSteps: Int?,
        matchedContact: ContactProfile?,
        savedContactsSummary: String,
        chatGuardHint: String,
        carromMemorySummary: String
    ): String {
        val historyBlock = if (recentHistory.isEmpty()) {
            "None yet (first step)."
        } else {
            recentHistory.takeLast(8).joinToString("\n") { log ->
                "Step ${log.stepNumber}: ${log.actionType.name} -> ${log.targetSummary} [${log.status.name}] (${log.reason})"
            }
        }

        val notifBlock = if (recentNotifications.isEmpty()) {
            "No new notifications."
        } else {
            recentNotifications.takeLast(5).joinToString("\n") { sig ->
                "[${sig.packageName}] ${sig.text}"
            }
        }

        val stepLabel = if (maxSteps != null) "$stepNumber of $maxSteps" else "$stepNumber (Continuous Monitor)"
        val memoryBlock = workingMemory.ifBlank { "No prior working memory yet." }
        val playbookBlock = playbook?.formatForPrompt() ?: "No persistent playbook saved yet."
        val outcomeBlock = lastOutcomeHint.ifBlank { "First observation — no prior gesture result yet." }
        val nodeTreeText = NodeTreeParser.formatNodesForPrompt(snapshot.nodes)
        val contactProfileSection = matchedContact?.formatForPrompt()
            ?: "No specific contact matched on this screen yet."
        val rosterSection = if (savedContactsSummary.isBlank()) {
            "No saved relationship contacts yet."
        } else {
            savedContactsSummary
        }
        val chatGuardSection = if (chatGuardHint.isBlank()) {
            ""
        } else {
            "\n            LIVE CHAT & TURN-TAKING STATUS:\n            $chatGuardHint\n"
        }
        val carromMemorySection = if (carromMemorySummary.isBlank()) {
            ""
        } else {
            "\n            $carromMemorySummary\n"
        }

        return """
            USER GOAL: $goal
            EXECUTION MODE: ${mode.name}
            STEP: $stepLabel
            FOREGROUND PACKAGE: ${snapshot.packageName}
            SCREEN RESOLUTION: ${snapshot.screenWidth}x${snapshot.screenHeight}
            CAPTURE STATUS: ${snapshot.captureNote}

            MATCHED CONTACT & RELATIONSHIP PERSONA:
            $contactProfileSection

            SAVED CONTACTS ROSTER (WhatsApp / WA Business / Facebook):
            $rosterSection
$chatGuardSection$carromMemorySection
            PERSISTENT PLAYBOOK (RESEARCHED & LEARNED ACROSS SESSIONS):
            $playbookBlock

            PREVIOUS ACTION RESULT TO EVALUATE:
            $outcomeBlock

            WORKING MEMORY:
            $memoryBlock

            RECENT INCOMING NOTIFICATIONS:
            $notifBlock

            RECENT HISTORY:
            $historyBlock

            UI NODE TREE (${snapshot.nodes.size} elements):
            $nodeTreeText
        """.trimIndent()
    }

    companion object {
        fun resolveVisionStrategy(
            settings: AgentSettings,
            interactiveNodes: Int,
            lastOutcomeHint: String,
            stepNumber: Int,
            forceVision: Boolean
        ): Pair<AiTaskProfile, Boolean> {
            val wasPreviousStepFailed = lastOutcomeHint.contains("[status=FAILED]", ignoreCase = true) ||
                lastOutcomeHint.contains("[NO SCREEN CHANGE", ignoreCase = true) ||
                lastOutcomeHint.contains("foul", ignoreCase = true)
            val isSparseCanvasOrGame = interactiveNodes < 5 || forceVision
            val taskProfile = if (stepNumber <= 1 || isSparseCanvasOrGame || wasPreviousStepFailed) {
                AiTaskProfile.HIGH_ACCURACY
            } else {
                AiTaskProfile.FAST_UI
            }
            val attachScreenshot = !(
                !forceVision &&
                    settings.smartTextFastPath &&
                    taskProfile == AiTaskProfile.FAST_UI &&
                    interactiveNodes >= 5 &&
                    !wasPreviousStepFailed
                )
            return taskProfile to attachScreenshot
        }
    }
}
