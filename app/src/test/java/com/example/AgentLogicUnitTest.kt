package com.example

import com.example.agent.AgentActionType
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AiProvider
import com.example.agent.PlannedAction
import com.example.agent.SafetyGuard
import com.example.agent.SafetyPolicyMode
import com.example.agent.ScreenBounds
import com.example.agent.ScreenNode
import com.example.agent.StepStatus
import com.example.ai.RestAiClients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogicUnitTest {

    @Test
    fun `payment only mode flags payment keyword on target node`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 4,
            x = 500,
            y = 1200,
            textToType = null,
            waitSeconds = null,
            memoryNote = null,
            reason = "Tap confirmation button",
            requiresConfirmation = false
        )
        val node = ScreenNode(
            id = 4,
            className = "android.widget.Button",
            text = "Pay Now",
            contentDesc = "",
            viewId = "btn_pay",
            bounds = ScreenBounds(300, 1150, 700, 1250),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val result = SafetyGuard.evaluate(action, node, SafetyPolicyMode.PAYMENT_ONLY)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `payment only mode allows facebook post and messenger send autonomously`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 6,
            x = 900,
            y = 1800,
            textToType = null,
            waitSeconds = null,
            memoryNote = "Posted in group",
            reason = "Tap Send button to reply on Messenger",
            requiresConfirmation = false
        )
        val node = ScreenNode(
            id = 6,
            className = "android.widget.ImageButton",
            text = "Send",
            contentDesc = "Send message",
            viewId = "btn_send",
            bounds = ScreenBounds(850, 1750, 950, 1850),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val paymentOnlyResult = SafetyGuard.evaluate(action, node, SafetyPolicyMode.PAYMENT_ONLY)
        assertFalse(paymentOnlyResult.needsConfirmation)

        val strictResult = SafetyGuard.evaluate(action, node, SafetyPolicyMode.STRICT_ALL)
        assertTrue(strictResult.needsConfirmation)
    }

    @Test
    fun `stuck loop ignores repeated monitor WAIT steps`() {
        val waitLog = AgentStepLog(
            stepNumber = 1,
            timestampMs = 100L,
            actionType = AgentActionType.WAIT,
            targetSummary = "Wait 30s",
            reason = "Waiting for reply",
            status = StepStatus.WAITING,
            requiresConfirmation = false
        )
        val candidateWait = PlannedAction(
            actionType = AgentActionType.WAIT,
            targetNodeId = null,
            x = null,
            y = null,
            textToType = null,
            waitSeconds = 30,
            memoryNote = null,
            reason = "Still waiting",
            requiresConfirmation = false
        )

        assertFalse(SafetyGuard.isStuckLoop(listOf(waitLog, waitLog, waitLog), candidateWait, "Wait 30s"))
    }

    @Test
    fun `stuck loop detects three identical executed tap steps`() {
        val log = AgentStepLog(
            stepNumber = 1,
            timestampMs = 100L,
            actionType = AgentActionType.TAP,
            targetSummary = "#3 Settings (400,600)",
            reason = "Open Settings",
            status = StepStatus.EXECUTED,
            requiresConfirmation = false
        )
        val candidate = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 3,
            x = 400,
            y = 600,
            textToType = null,
            waitSeconds = null,
            memoryNote = null,
            reason = "Open Settings",
            requiresConfirmation = false
        )

        assertTrue(SafetyGuard.isStuckLoop(listOf(log, log, log), candidate, "#3 Settings (400,600)"))
    }

    @Test
    fun `rest client extracts raw json from fenced third party model output`() {
        val fenced = """
            ```json
            {"actionType":"TAP","x":240,"y":680,"reason":"Tap Post","requiresConfirmation":false}
            ```
        """.trimIndent()
        val extracted = RestAiClients.extractJsonObjectString(fenced)
        assertEquals(
            "{\"actionType\":\"TAP\",\"x\":240,\"y\":680,\"reason\":\"Tap Post\",\"requiresConfirmation\":false}",
            extracted
        )
    }

    @Test
    fun `agent settings resolves active key and model per provider`() {
        val settings = AgentSettings(
            aiProvider = AiProvider.OPENROUTER,
            googleAiStudioApiKey = "g_key_12345",
            groqApiKey = "gsk_key_12345",
            openRouterApiKey = "  or_key_123  ",
            openRouterModel = "openrouter/free"
        )
        assertEquals("or_key_123", settings.activeApiKey())
        assertEquals("openrouter/free", settings.activeModelName())
    }

    @Test
    fun `parseApiKeys handles 25 plus keys across newlines commas and spaces without duplicates`() {
        val raw = (1..28).joinToString(separator = "\n") { idx ->
            when {
                idx % 3 == 0 -> "  AIzaSyKeyNumber_$idx, AIzaSyKeyNumber_$idx  "
                idx % 2 == 0 -> "AIzaSyKeyNumber_$idx;"
                else -> "AIzaSyKeyNumber_$idx"
            }
        }
        val parsed = AgentSettings.parseApiKeys(raw)
        assertEquals(28, parsed.size)
        assertEquals("AIzaSyKeyNumber_1", parsed.first())
        assertEquals("AIzaSyKeyNumber_28", parsed.last())
    }

    @Test
    fun `sticky failover skips failed keys and resumes from healthy key on subsequent steps`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "key_one_11111\nkey_two_22222\nkey_three_33333",
            autoCrossProviderFailover = false,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        val step1Calls = mutableListOf<String>()
        val step1Result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, apiKey, _ ->
            step1Calls.add(apiKey)
            if (apiKey == "key_one_11111") throw IllegalStateException("HTTP 429 rate limit")
            "ok_from_$apiKey"
        }

        assertEquals(listOf("key_one_11111", "key_two_22222"), step1Calls)
        assertEquals("ok_from_key_two_22222", step1Result.value)
        assertEquals(2, step1Result.keyIndexOneBased)
        assertEquals(1, step1Result.failoverCount)

        // Step 2 must start directly at key_two_22222 without wasting a call on failed key_one_11111
        val step2Calls = mutableListOf<String>()
        val step2Result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, apiKey, _ ->
            step2Calls.add(apiKey)
            "step2_$apiKey"
        }

        assertEquals(listOf("key_two_22222"), step2Calls)
        assertEquals("step2_key_two_22222", step2Result.value)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `smart hybrid round robin spreads one call per step across providers and keys`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyGoogleKey0001\nAIzaSyGoogleKey0002",
            groqApiKey = "gsk_GroqKey00000001\ngsk_GroqKey00000002",
            openRouterApiKey = "sk-or-v1-OpenRouter01\nsk-or-v1-OpenRouter02",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.SMART_HYBRID
        )

        val usedRoutes = mutableListOf<String>()
        repeat(6) {
            val res = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
                settings = settings,
                firebaseConfigured = false,
                taskProfile = com.example.ai.AiTaskProfile.FAST_UI
            ) { provider, apiKey, _ ->
                "${provider.name}:$apiKey"
            }
            usedRoutes.add(res.value)
        }

        // All 6 distinct keys across the 3 providers must be used exactly once across 6 steps (1 call per step!)
        assertEquals(6, usedRoutes.distinct().size)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `rate limited key enters 65s temporary cooldown and auto recovers while 401 stays invalid`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        var fakeClock = 1_000_000L
        com.example.ai.ApiKeyFailoverManager.nowMsProvider = { fakeClock }

        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyRateLimited01\nAIzaSyHealthyKey002",
            autoCrossProviderFailover = false,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        // Step 1: Key 1 hits HTTP 429 -> falls back to Key 2 and places Key 1 in 65s cooldown
        val res1 = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, key, _ ->
            if (key == "AIzaSyRateLimited01") throw IllegalStateException("HTTP 429 quota exceeded")
            key
        }
        assertEquals("AIzaSyHealthyKey002", res1.value)
        assertEquals(1, com.example.ai.ApiKeyFailoverManager.getCooldownCount(AiProvider.GOOGLE_AI_STUDIO))
        assertEquals(0, com.example.ai.ApiKeyFailoverManager.getFailedCount(AiProvider.GOOGLE_AI_STUDIO))

        // After 66 seconds, cooldown expires automatically and Key 1 is healthy again!
        fakeClock += 66_000L
        assertEquals(0, com.example.ai.ApiKeyFailoverManager.getCooldownCount(AiProvider.GOOGLE_AI_STUDIO))

        // If Key 2 fails with HTTP 401, Key 1 immediately recovers and runs without manual reset
        val res2 = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, key, _ ->
            if (key == "AIzaSyHealthyKey002") throw IllegalStateException("HTTP 401 Unauthorized")
            key
        }
        assertEquals("AIzaSyRateLimited01", res2.value)
        assertEquals(1, com.example.ai.ApiKeyFailoverManager.getFailedCount(AiProvider.GOOGLE_AI_STUDIO))
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `failover manager crosses to groq and openrouter when primary provider keys exhaust`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "google_bad_1234",
            groqApiKey = "gsk_bad_123456",
            openRouterApiKey = "sk-or-v1-good12345",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        val attemptedProviders = mutableListOf<AiProvider>()
        val result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { provider, apiKey, model ->
            attemptedProviders.add(provider)
            if (provider != AiProvider.OPENROUTER) throw IllegalStateException("Quota exhausted")
            "$provider:$model:$apiKey"
        }

        assertEquals(
            listOf(AiProvider.GOOGLE_AI_STUDIO, AiProvider.GROQ_CLOUD, AiProvider.OPENROUTER),
            attemptedProviders
        )
        assertEquals(AiProvider.OPENROUTER, result.providerUsed)
        assertEquals("openrouter/free", result.modelUsed)
        assertEquals(2, result.failoverCount)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `bulk key importer parses mixed txt csv json and sorts by provider prefix`() {
        val mixedBulkText = """
            # Mixed keys backup
            GOOGLE_KEY_1="AIzaSyBulkGoogleKey00001"
            AIzaSyBulkGoogleKey00002, gsk_BulkGroqKey00000001
            {"openrouter": ["sk-or-v1-BulkOpenRouter001", "sk-or-v1-BulkOpenRouter002"]}
            gsk_BulkGroqKey00000002
            AIzaSyBulkGoogleKey00001
        """.trimIndent()

        val (updated, summary) = com.example.ai.BulkKeyImporter.importIntoSettings(
            rawContent = mixedBulkText,
            currentSettings = AgentSettings(),
            fallbackProvider = AiProvider.GOOGLE_AI_STUDIO
        )

        assertEquals(2, summary.googleCount)
        assertEquals(2, summary.groqCount)
        assertEquals(2, summary.openRouterCount)
        assertEquals(6, summary.newlyAddedCount)
        assertEquals(2, updated.apiKeysFor(AiProvider.GOOGLE_AI_STUDIO).size)
        assertEquals(2, updated.apiKeysFor(AiProvider.GROQ_CLOUD).size)
        assertEquals(2, updated.apiKeysFor(AiProvider.OPENROUTER).size)

        val (redistributed, sortedSummary) = com.example.ai.BulkKeyImporter.redistributeMixedBlock(
            mixedText = "AIzaSyOnlyGoogle00003\ngsk_OnlyGroq00000003",
            currentSettings = AgentSettings(googleAiStudioApiKey = "AIzaSyOnlyGoogle00003\ngsk_OnlyGroq00000003"),
            sourceProvider = AiProvider.GOOGLE_AI_STUDIO
        )
        assertEquals(1, sortedSummary.googleCount)
        assertEquals(1, sortedSummary.groqCount)
        assertEquals(listOf("AIzaSyOnlyGoogle00003"), redistributed.apiKeysFor(AiProvider.GOOGLE_AI_STUDIO))
        assertEquals(listOf("gsk_OnlyGroq00000003"), redistributed.apiKeysFor(AiProvider.GROQ_CLOUD))
    }

    @Test
    fun `knowledge store matches saved carrom playbook by goal and merges lessons without duplicates`() {
        val carrom = com.example.agent.TaskPlaybook(
            topicKey = "carrom_board_game",
            topicTitle = "Carrom Board Game",
            rulesSummary = "Drag striker opposite target direction.",
            winningStrategies = listOf("Cut shots at 30 degrees work well for corner pockets."),
            foulLessons = listOf("Pocketing striker causes a foul.")
        )
        val matched = com.example.agent.AgentKnowledgeStore.findBestMatchingPlaybook(
            listOf(carrom),
            "Open Carrom Board and practice pocketing coins"
        )
        assertEquals("carrom_board_game", matched?.topicKey)

        val merged = com.example.agent.AgentKnowledgeStore.mergeLessons(
            carrom.foulLessons,
            listOf("pocketing striker causes a foul.", "Avoid hitting opponent black coin first.")
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `turbo dual key hedge uses single call when primary is fast and races secondary when primary is slow`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        com.example.ai.ApiKeyFailoverManager.hedgeDelayMs = 80L

        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyPrimaryFast001",
            groqApiKey = "gsk_SecondaryFast002",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER,
            turboDualKeyGameMode = true
        )

        // Case A: Primary finishes before hedgeDelay -> secondary is cancelled without making any call
        val fastCalls = java.util.Collections.synchronizedList(mutableListOf<AiProvider>())
        val fastResult = com.example.ai.ApiKeyFailoverManager.executeWithHedgeOrFailover(
            settings = settings,
            firebaseConfigured = false,
            taskProfile = com.example.ai.AiTaskProfile.HIGH_ACCURACY,
            enableHedgeRace = true
        ) { provider, _, _ ->
            fastCalls.add(provider)
            "shot_from_${provider.name}"
        }
        assertEquals("shot_from_GOOGLE_AI_STUDIO", fastResult.value)
        assertEquals(listOf(AiProvider.GOOGLE_AI_STUDIO), fastCalls)

        // Case B: Primary is slow (> 80ms hedgeDelay) -> secondary starts in parallel and wins immediately
        val slowCalls = java.util.Collections.synchronizedList(mutableListOf<AiProvider>())
        val slowResult = com.example.ai.ApiKeyFailoverManager.executeWithHedgeOrFailover(
            settings = settings,
            firebaseConfigured = false,
            taskProfile = com.example.ai.AiTaskProfile.HIGH_ACCURACY,
            enableHedgeRace = true
        ) { provider, _, _ ->
            slowCalls.add(provider)
            if (provider == AiProvider.GOOGLE_AI_STUDIO) {
                kotlinx.coroutines.delay(900L)
                "late_google"
            } else {
                "fast_groq_win"
            }
        }
        assertEquals("fast_groq_win", slowResult.value)
        assertEquals(AiProvider.GROQ_CLOUD, slowResult.providerUsed)
        assertTrue(slowResult.wasHedged)
        assertTrue(slowCalls.containsAll(listOf(AiProvider.GOOGLE_AI_STUDIO, AiProvider.GROQ_CLOUD)))
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `action cache verifies screen transition matches shifted live node and blocks game boards`() {
        fun node(id: Int, label: String, viewId: String, left: Int, top: Int): ScreenNode = ScreenNode(
            id = id,
            className = "android.widget.Button",
            text = label,
            contentDesc = "",
            viewId = viewId,
            bounds = ScreenBounds(left, top, left + 180, top + 80),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val lobbyNodes = listOf(
            node(1, "Play Classic", "btn_play", 100, 300),
            node(2, "Disc Pool", "btn_pool", 100, 420),
            node(3, "Friends", "btn_friends", 100, 540),
            node(4, "Shop", "btn_shop", 100, 660)
        )
        val sigBefore = com.example.agent.ActionCacheStore.computeScreenSignature(lobbyNodes)
        val playAction = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 1,
            x = 190,
            y = 340,
            textToType = null,
            waitSeconds = null,
            memoryNote = null,
            reason = "Tap Play Classic",
            requiresConfirmation = false
        )
        val candidate = com.example.agent.PendingCacheCandidate(
            goal = "Open Carrom and play classic match",
            packageName = "com.miniclip.carrom",
            screenSignature = sigBefore,
            interactiveNodeCount = 4,
            action = playAction,
            targetNode = lobbyNodes[0],
            hadConfirmedSafety = false
        )

        // Screen didn't change -> ineligible
        val unchangedSnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, lobbyNodes, null, "")
        assertFalse(com.example.agent.ActionCacheStore.isEligibleForCache(candidate, unchangedSnapshot))

        // Screen transitioned to mode selection -> eligible!
        val nextNodes = listOf(
            node(1, "Matchmaking", "btn_match", 200, 500),
            node(2, "Cancel", "btn_cancel", 200, 700),
            node(3, "Paris", "btn_paris", 200, 900),
            node(4, "Delhi", "btn_delhi", 200, 1100)
        )
        val nextSnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, nextNodes, null, "")
        assertTrue(com.example.agent.ActionCacheStore.isEligibleForCache(candidate, nextSnapshot))

        // Sparse game board (< 4 clickable nodes) -> never eligible for cache
        val sparseCandidate = candidate.copy(interactiveNodeCount = 1)
        assertFalse(com.example.agent.ActionCacheStore.isEligibleForCache(sparseCandidate, nextSnapshot))

        // Cached entry resolves against shifted live node coordinates cleanly
        val goalKeys = com.example.agent.ActionCacheStore.normalizeGoalKeywords("Open Carrom and play classic match")
        val savedEntry = com.example.agent.CachedActionEntry(
            cacheKey = com.example.agent.ActionCacheStore.buildCacheKey(goalKeys, "com.miniclip.carrom", sigBefore),
            goalKeywords = goalKeys,
            packageName = "com.miniclip.carrom",
            screenSignature = sigBefore,
            actionType = AgentActionType.TAP,
            targetLabel = "Play Classic",
            targetViewId = "btn_play",
            tapX = 190,
            tapY = 340,
            textToType = null
        )

        val shiftedLobby = listOf(
            node(9, "Play Classic", "btn_play", 400, 800),
            node(2, "Disc Pool", "btn_pool", 100, 420),
            node(3, "Friends", "btn_friends", 100, 540),
            node(4, "Shop", "btn_shop", 100, 660)
        )
        val replaySnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, shiftedLobby, null, "")
        val match = com.example.agent.ActionCacheStore.matchEntryFromList(listOf(savedEntry), "Play Carrom classic", replaySnapshot)
        assertEquals(9, match?.action?.targetNodeId)
        assertEquals(490, match?.action?.x)
        assertEquals(840, match?.action?.y)
    }

    @Test
    fun `carrom solver computes slingshot ghost ball shot avoiding obstacles and foul pockets`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f), // top-left
                com.example.game.Point2D(960f, 560f), // top-right
                com.example.game.Point2D(120f, 1400f), // bottom-left
                com.example.game.Point2D(960f, 1400f)  // bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // White target coin near top-left; black obstacle blocks top-right pocket path
        val whiteTarget = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(310f, 750f),
            radiusPx = 28f
        )
        val blockingBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(635f, 655f), // directly between (310,750) and top-right (960,560)
            radiusPx = 28f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteTarget, blockingBlack),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(solution)
        val sol = solution!!
        // Should aim for top-left pocket (120, 560), NOT blocked top-right
        assertEquals(120f, sol.targetPocket.x, 1f)
        assertEquals(560f, sol.targetPocket.y, 1f)
        // Slingshot pulls back opposite the upward shot (dragEnd.y > strikerPos.y)
        assertTrue(sol.dragEnd.y > sol.strikerPos.y)
        assertFalse(sol.isBankShot)

        // Straight-on follow-through into pocket must be flagged as a striker scratch foul
        val dirToTopLeft = (com.example.game.Point2D(120f, 560f) - whiteTarget.center).normalized()
        val scratchGhost = whiteTarget.center - dirToTopLeft * 63f
        assertTrue(
            com.example.game.CarromGeometrySolver.willStrikerScratchIntoPocket(
                strikerPos = com.example.game.Point2D(500f, 1320f),
                ghostBall = scratchGhost,
                dirSg = dirToTopLeft,
                dirCp = dirToTopLeft,
                cutAngleDeg = 0f,
                board = board
            )
        )
    }

    @Test
    fun `carrom solver falls back to one rail bank shot when direct pocket paths are blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(540f, 820f),
            radiusPx = 28f
        )
        // Place 4 blocker black coins between whiteCoin and each of the 4 pockets
        val blockers = board.pockets.map { pocket ->
            val mid = com.example.game.Point2D(
                (whiteCoin.center.x + pocket.x) * 0.5f,
                (whiteCoin.center.y + pocket.y) * 0.5f
            )
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, mid, 28f)
        }

        val bankShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteCoin) + blockers,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(bankShot)
        assertTrue(bankShot!!.isBankShot)
    }

    @Test
    fun `carrom solver shoots from existing striker position when slider repositioning is unavailable`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val currentStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(620f, 1320f),
            radiusPx = 35f
        )
        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(360f, 800f),
            radiusPx = 28f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(currentStriker, whiteCoin),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = currentStriker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertEquals(620f, solution!!.strikerPos.x, 1f)
        assertEquals(1320f, solution.strikerPos.y, 1f)
    }

    @Test
    fun `carrom solver applies stronger pull power for angled cut shots than straight shots`() {
        val striker = com.example.game.Point2D(540f, 1320f)
        val shotDir = com.example.game.Point2D(0f, -1f)

        val straightEnd = com.example.game.CarromGeometrySolver.computeSlingshotDragEnd(
            strikerPos = striker,
            shotDir = shotDir,
            totalDistance = 750f,
            boardWidth = 960f,
            screenWidth = 1080,
            screenHeight = 2400,
            cutAngleDeg = 0f
        )
        val cutEnd = com.example.game.CarromGeometrySolver.computeSlingshotDragEnd(
            strikerPos = striker,
            shotDir = shotDir,
            totalDistance = 750f,
            boardWidth = 960f,
            screenWidth = 1080,
            screenHeight = 2400,
            cutAngleDeg = 48f
        )

        val straightPull = (straightEnd - striker).length()
        val cutPull = (cutEnd - striker).length()
        assertTrue("Expected angled cut pull ($cutPull) > straight pull ($straightPull)", cutPull > straightPull + 25f)
    }

    @Test
    fun `carrom solver finds two coin combo shot when direct path to target coin is blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // finalWhite sits cleanly lined up with top-left pocket (120, 560),
        // helperWhite sits behind finalWhite along that pocket axis,
        // and a black blocker guards direct striker access to finalWhite from baseline.
        val finalWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(260f, 700f),
            radiusPx = 28f
        )
        val helperWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(380f, 820f),
            radiusPx = 28f
        )
        val guardBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(350f, 960f),
            radiusPx = 28f
        )
        val currentStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(finalWhite, helperWhite, guardBlack, currentStriker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = currentStriker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertTrue(solution!!.dragEnd.y > solution.strikerPos.y)
    }

    @Test
    fun `carrom solver falls back to power cluster break when all pockets are blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val centerWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(540f, 980f),
            radiusPx = 28f
        )
        // Surround centerWhite with 6 black blockers blocking both direct and bank paths
        val blockers = listOf(
            com.example.game.Point2D(460f, 900f),
            com.example.game.Point2D(620f, 900f),
            com.example.game.Point2D(460f, 1060f),
            com.example.game.Point2D(620f, 1060f),
            com.example.game.Point2D(540f, 890f),
            com.example.game.Point2D(360f, 980f),
            com.example.game.Point2D(720f, 980f)
        ).map { com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, it, 28f) }

        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(centerWhite, striker) + blockers,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertTrue(solution!!.reason.contains("Power Break", ignoreCase = true) || solution.confidenceScore >= 0.50f)
    }

    @Test
    fun `contact profile store serializes parses and matches by platform and peer name`() {
        val rahimFb = com.example.agent.ContactProfile(
            id = "c1",
            name = "Rahim",
            platform = com.example.agent.ChatPlatform.FACEBOOK_MESSENGER,
            relationship = com.example.agent.RelationshipType.FRIEND,
            customStyleNote = "Call him mama",
            autoReplyEnabled = true,
            createdAtMs = 1000L
        )
        val saraWa = com.example.agent.ContactProfile(
            id = "c2",
            name = "Sara",
            platform = com.example.agent.ChatPlatform.WHATSAPP,
            relationship = com.example.agent.RelationshipType.GF,
            customStyleNote = "Sweet warm tone",
            autoReplyEnabled = true,
            createdAtMs = 2000L
        )
        val pausedBro = com.example.agent.ContactProfile(
            id = "c3",
            name = "Karim",
            platform = com.example.agent.ChatPlatform.BOTH,
            relationship = com.example.agent.RelationshipType.BROTHER,
            autoReplyEnabled = false,
            createdAtMs = 3000L
        )

        val contacts = listOf(rahimFb, saraWa, pausedBro)

        // Rahim saved for Facebook matches in Messenger, NOT WhatsApp
        val fbMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Rahim",
            packageName = "com.facebook.orca",
            goal = "Chat with friend",
            recentNotifications = emptyList()
        )
        assertEquals("c1", fbMatch?.id)

        val wrongPlatformMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Rahim",
            packageName = "com.whatsapp",
            goal = "Chat with friend",
            recentNotifications = emptyList()
        )
        assertEquals(null, wrongPlatformMatch)

        // Sara matches in WhatsApp
        val waMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Sara",
            packageName = "com.whatsapp",
            goal = "Reply messages",
            recentNotifications = emptyList()
        )
        assertEquals("c2", waMatch?.id)

        // Paused contact is skipped
        val pausedMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Karim",
            packageName = "com.facebook.orca",
            goal = "Reply",
            recentNotifications = emptyList()
        )
        assertEquals(null, pausedMatch)
    }

    @Test
    fun `chat turn guard blocks repeat messages without reply and unlocks on real reply`() {
        fun chatNode(
            id: Int,
            text: String,
            top: Int,
            bottom: Int,
            left: Int = 180,
            right: Int = 780,
            editable: Boolean = false,
            clickable: Boolean = false,
            viewId: String = ""
        ) = ScreenNode(
            id = id,
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            text = text,
            contentDesc = "",
            viewId = viewId,
            bounds = ScreenBounds(left, top, right, bottom),
            isClickable = clickable,
            isEditable = editable,
            isScrollable = false,
            isChecked = false,
            isFocused = editable
        )

        val headerNode = chatNode(1, "Rahim Ahmed", top = 120, bottom = 190, viewId = "thread_title")
        val initialIncoming = chatNode(2, "Kire ki obostha?", top = 900, bottom = 980)
        val ourBubble = chatNode(3, "কি খবর বন্ধু", top = 1100, bottom = 1180)
        val composeBox = chatNode(4, "Message", top = 2100, bottom = 2220, editable = true, viewId = "composer_edit_text")
        val sendBtn = chatNode(5, "Send", top = 2100, bottom = 2220, left = 920, right = 1040, clickable = true, viewId = "send_button")

        val postSendSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.facebook.orca",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(headerNode, initialIncoming, ourBubble, composeBox, sendBtn),
            screenshot = null,
            captureNote = ""
        )

        val obsAfterSend = com.example.agent.ChatTurnGuard.extractChatObservation(postSendSnapshot)
        assertTrue(obsAfterSend.isMessagingApp)
        assertTrue(obsAfterSend.inChatThread)
        assertEquals("Rahim Ahmed", obsAfterSend.peerName)
        assertEquals("কি খবর বন্ধু", obsAfterSend.lastMessageText)

        // State captured right before our bubble rendered
        val awaiting = com.example.agent.ChatTurnGuard.AwaitingReplyState(
            packageName = "com.facebook.orca",
            peerName = "Rahim Ahmed",
            lastSentText = "কি খবর বন্ধু",
            transcriptSignatureAtSend = "before_send_sig",
            sentAtMs = 5_000L
        )

        // Even though transcriptSignature changed (because our own bubble appeared), must NOT count as incoming reply!
        assertFalse(
            com.example.agent.ChatTurnGuard.didIncomingReplyArrive(
                obs = obsAfterSend,
                recentNotifications = emptyList(),
                awaiting = awaiting
            )
        )

        val duplicateTypeAction = PlannedAction(
            actionType = AgentActionType.INPUT_TEXT,
            targetNodeId = 4,
            x = 400,
            y = 2160,
            textToType = "কি খবর বন্ধু? কই গেলা?",
            waitSeconds = null,
            memoryNote = null,
            reason = "Follow up with friend",
            requiresConfirmation = false
        )
        assertTrue(
            com.example.agent.ChatTurnGuard.shouldBlockDuplicateSend(
                action = duplicateTypeAction,
                targetNode = composeBox,
                obs = obsAfterSend,
                recentNotifications = emptyList(),
                awaiting = awaiting,
                snapshot = postSendSnapshot
            )
        )

        // Once friend actually replies on screen, guard unlocks immediately!
        val friendReplyBubble = chatNode(6, "Ei to valo re dost, tui kemon?", top = 1280, bottom = 1360)
        val replySnapshot = postSendSnapshot.copy(
            nodes = listOf(headerNode, initialIncoming, ourBubble, friendReplyBubble, composeBox, sendBtn)
        )
        val obsAfterReply = com.example.agent.ChatTurnGuard.extractChatObservation(replySnapshot)
        assertTrue(
            com.example.agent.ChatTurnGuard.didIncomingReplyArrive(
                obs = obsAfterReply,
                recentNotifications = emptyList(),
                awaiting = awaiting
            )
        )
        assertFalse(
            com.example.agent.ChatTurnGuard.shouldBlockDuplicateSend(
                action = duplicateTypeAction,
                targetNode = composeBox,
                obs = obsAfterReply,
                recentNotifications = emptyList(),
                awaiting = awaiting,
                snapshot = replySnapshot
            )
        )
    }

    @Test
    fun `carrom shot memory learns winning pockets softens power after fouls and guides future shots`() {
        var mem = com.example.game.CarromMemoryData()

        // 1. Successful pot in Top-Left pocket (index 0) at 20 deg cut with In-Place shot
        val winShot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 0,
            cutAngleDeg = 20f,
            powerStyle = com.example.game.CarromPowerStyle.MEDIUM,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        )
        val winEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = winShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 5, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.POCKETED_MY_COIN, winEval.outcomeType)
        org.junit.Assert.assertNotNull(winEval.learnedTacticLesson)
        mem = winEval.updatedMemory
        assertTrue(mem.confidenceBiasFor(0, 20f) > 0f)

        // 2. Foul on Top-Right pocket (index 1) straight shot (myCount increased due to penalty coin returned!)
        val foulShot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 1,
            cutAngleDeg = 6f,
            powerStyle = com.example.game.CarromPowerStyle.HARD,
            movedStrikerWithSlider = true,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 5, queenCount = 1)
        )
        val foulEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = foulShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY, foulEval.outcomeType)
        org.junit.Assert.assertNotNull(foulEval.learnedFoulLesson)
        mem = foulEval.updatedMemory
        assertTrue(mem.learnedPowerScaleFor(1, 6f) < 1.0f)
        assertTrue(mem.confidenceBiasFor(1, 6f) < 0f)
        assertTrue(mem.needsExtraScratchGuard())

        // 3. Symmetric board: leftWhite aims Top-Left (0), rightWhite aims Top-Right (1).
        // With learned memory favoring Top-Left & penalizing Top-Right, solver picks Top-Left!
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val leftWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(320f, 760f),
            radiusPx = 28f
        )
        val rightWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(760f, 760f),
            radiusPx = 28f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val guidedSolution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(leftWhite, rightWhite, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            shotMemory = mem
        )

        org.junit.Assert.assertNotNull(guidedSolution)
        assertEquals(0, guidedSolution!!.pocketIndex)
    }

    @Test
    fun `forceVision keeps screenshot and high accuracy for carrom even with 8 HUD buttons`() {
        val settings = AgentSettings(smartTextFastPath = true)

        // Normal UI screen on step 3 with 8 buttons uses FAST_UI without screenshot
        val (uiProfile, uiAttach) = com.example.ai.GeminiAiPlanner.resolveVisionStrategy(
            settings = settings,
            interactiveNodes = 8,
            lastOutcomeHint = "",
            stepNumber = 3,
            forceVision = false
        )
        assertEquals(com.example.ai.AiTaskProfile.FAST_UI, uiProfile)
        assertFalse(uiAttach)

        // Carrom match screen with 8 HUD buttons (chat, emoji, mic, avatar, gift, settings) keeps HIGH_ACCURACY + screenshot
        val (carromProfile, carromAttach) = com.example.ai.GeminiAiPlanner.resolveVisionStrategy(
            settings = settings,
            interactiveNodes = 8,
            lastOutcomeHint = "",
            stepNumber = 3,
            forceVision = true
        )
        assertEquals(com.example.ai.AiTaskProfile.HIGH_ACCURACY, carromProfile)
        assertTrue(carromAttach)
    }

    @Test
    fun `carrom context matches bengali spellings and distinguishes match HUD from lobby menu`() {
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = "ক্যারম খেলো",
                packageName = "com.unknown.game",
                playbookTopicKey = null
            )
        )
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = "কেরাম বোর্ড",
                packageName = "com.example",
                playbookTopicKey = null
            )
        )

        fun hudNode(id: Int, label: String) = ScreenNode(
            id = id,
            className = "android.widget.ImageButton",
            text = label,
            contentDesc = label,
            viewId = "hud_$id",
            bounds = ScreenBounds(10, 10, 90, 90),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val matchHudNodes = listOf(
            hudNode(1, "Chat"),
            hudNode(2, "Emoji"),
            hudNode(3, "Mic"),
            hudNode(4, "Gift"),
            hudNode(5, "Settings"),
            hudNode(6, "Pause")
        )
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(matchHudNodes))

        val lobbyNodes = matchHudNodes + hudNode(7, "Play Classic")
        assertTrue(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(lobbyNodes))
    }

    @Test
    fun `carrom geometry solver falls back to available coins in freestyle and guarantees break shot`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1312f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1312f),
            radiusPx = 35f
        )

        // 1. Freestyle/late-game: no WHITE or QUEEN coins remain, only BLACK coins
        val onlyBlack = listOf(
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(520f, 960f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(580f, 980f), 28f),
            striker
        )
        val freestyleShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = onlyBlack,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )
        org.junit.Assert.assertNotNull(freestyleShot)

        // 2. Tight center break rack with fixed center striker must still guarantee a break shot
        val breakRack = listOf(
            com.example.game.CarromPiece(com.example.game.CoinColor.QUEEN, com.example.game.Point2D(540f, 980f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(540f, 930f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(584f, 955f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(496f, 955f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(540f, 1030f), 28f),
            striker
        )
        val breakShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = breakRack,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )
        org.junit.Assert.assertNotNull(breakShot)
        assertTrue(breakShot!!.confidenceScore >= 0.50f)
    }

    @Test
    fun `2d physics rollout simulator avoids scratch foul and picks clean verified cut shot`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        // Candidate A: Top-Left coin strictly collinear with striker -> pocket[0] (0 deg cut -> striker scratches!)
        val riskyTopLeft = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(225f, 750f),
            radiusPx = 28f
        )
        // Candidate B: Clean angled cut toward Top-Right pocket where striker deflects safely away from pocket
        val safeTopRight = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(760f, 700f),
            radiusPx = 28f
        )

        val dirRisky = (riskyTopLeft.center - striker.center).normalized()
        val riskyCand = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = dirRisky,
            ghostBall = riskyTopLeft.center - (board.pockets[0] - riskyTopLeft.center).normalized() * 63f,
            targetCoin = riskyTopLeft,
            targetPocket = board.pockets[0],
            pocketIndex = 0,
            cutAngleDeg = 0f,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = 1.25f,
            staticScore = 0.85f,
            totalDistance = (board.pockets[0] - striker.center).length()
        )

        val ghostSafe = safeTopRight.center - (board.pockets[1] - safeTopRight.center).normalized() * 63f
        val safeCand = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = (ghostSafe - striker.center).normalized(),
            ghostBall = ghostSafe,
            targetCoin = safeTopRight,
            targetPocket = board.pockets[1],
            pocketIndex = 1,
            cutAngleDeg = 22f,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = 1.05f,
            staticScore = 0.84f,
            totalDistance = (board.pockets[1] - striker.center).length()
        )

        val verified = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = listOf(riskyTopLeft, safeTopRight, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(riskyCand, safeCand),
            shotMemory = null,
            matchCalibration = null
        )

        org.junit.Assert.assertNotNull(verified)
        val (bestCand, res) = verified!!
        assertTrue(res.rolloutsRun >= 15)
        assertFalse(res.predictedScratchFoul)
        assertEquals(1, bestCand.pocketIndex)
    }

    @Test
    fun `scratch zone heatmap and match physics calibration track fouls and decay on clean pots`() {
        var mem = com.example.game.CarromMemoryData()
        val foulShot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 1,
            cutAngleDeg = 8f,
            strikerNormalizedX = 0.72f,
            powerStyle = com.example.game.CarromPowerStyle.HARD,
            movedStrikerWithSlider = true,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 4, queenCount = 1)
        )

        val foulEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = foulShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 4, queenCount = 1)
        )
        mem = foulEval.updatedMemory

        val zoneRiskAfterFoul = mem.scratchHeatmap.riskFor(1, 8f, 0.72f)
        assertTrue("Expected elevated scratch zone risk > 0.25, got $zoneRiskAfterFoul", zoneRiskAfterFoul > 0.25f)
        assertTrue(mem.matchCalibration.pocketJawSafetyBuffer > 0f)
        assertEquals(1, mem.matchCalibration.shotsThisMatch)

        val cleanShot = foulShot.copy(preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 4, queenCount = 1))
        val cleanEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = cleanShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 4, queenCount = 1)
        )
        val decayedRisk = cleanEval.updatedMemory.scratchHeatmap.riskFor(1, 8f, 0.72f)
        assertTrue("Expected clean pot to decay zone risk ($decayedRisk < $zoneRiskAfterFoul)", decayedRisk < zoneRiskAfterFoul)
    }

    @Test
    fun `physics rollout detects target kiss deflection and picks zero-kiss foul-free sure shot`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        // Coin A (top-left) has a black coin brushing its forward shoulder near the edge -> kiss deflection makes it miss
        val kissCoinA = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(320f, 760f),
            radiusPx = 28f
        )
        val kissBlocker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(245f, 710f), // overlaps coin-to-pocket corridor by ~18px
            radiusPx = 28f
        )

        // Coin B (top-right) has a completely open path -> clean Sure Shot
        val clearCoinB = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(760f, 760f),
            radiusPx = 28f
        )

        val ghostA = kissCoinA.center - (board.pockets[0] - kissCoinA.center).normalized() * 63f
        val candA = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = (ghostA - striker.center).normalized(),
            ghostBall = ghostA,
            targetCoin = kissCoinA,
            targetPocket = board.pockets[0],
            pocketIndex = 0,
            cutAngleDeg = 18f,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = 1.0f,
            staticScore = 0.86f,
            totalDistance = (board.pockets[0] - striker.center).length()
        )

        val ghostB = clearCoinB.center - (board.pockets[1] - clearCoinB.center).normalized() * 63f
        val candB = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = (ghostB - striker.center).normalized(),
            ghostBall = ghostB,
            targetCoin = clearCoinB,
            targetPocket = board.pockets[1],
            pocketIndex = 1,
            cutAngleDeg = 18f,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = 1.0f,
            staticScore = 0.84f,
            totalDistance = (board.pockets[1] - striker.center).length()
        )

        val verified = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = listOf(kissCoinA, kissBlocker, clearCoinB, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(candA, candB),
            shotMemory = null,
            matchCalibration = null
        )

        org.junit.Assert.assertNotNull(verified)
        val (winner, result) = verified!!
        assertEquals(1, winner.pocketIndex)
        assertFalse(result.predictedKissBlock)
        assertFalse(result.predictedScratchFoul)
        assertTrue(result.isSureShot)
    }

    @Test
    fun `turn memory enforces 7s clock allows up to 6 slides in budget and fuses multi-frame scans`() {
        val tm = com.example.game.CarromTurnMemory()
        val t0 = 10_000L

        assertEquals(7_000L, com.example.game.CarromTurnMemory.MAX_TURN_BUDGET_MS)
        assertEquals(6_350L, com.example.game.CarromTurnMemory.FALLBACK_FIRE_ELAPSED_MS)
        assertEquals(6, com.example.game.CarromTurnMemory.MAX_BASELINE_PROBES)

        val coin1Frame1 = com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(300f, 700f), 28f)
        val coin2Frame1 = com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(600f, 800f), 28f)
        val striker1 = com.example.game.CarromPiece(com.example.game.CoinColor.STRIKER, com.example.game.Point2D(540f, 1320f), 35f)

        // Frame 1 (pre-slide): can slide while under 6 moves and within 7s budget
        tm.recordObservation(t0, listOf(coin1Frame1, coin2Frame1), striker1, com.example.game.Point2D(540f, 1600f), null, null)
        assertTrue(tm.canAffordAnotherSliderMove(t0 + 1_000L, isLiveStreamActive = true))
        tm.recordSliderMoveDispatched(540f)

        // Frame 2 (after slide #1): slight jitter on coin1, slider gain refined
        val coin1Frame2 = com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(304f, 698f), 28f)
        tm.recordObservation(t0 + 1_200L, listOf(coin1Frame2, coin2Frame1), striker1.copy(center = com.example.game.Point2D(420f, 1320f)), com.example.game.Point2D(420f, 1600f), null, 0.92f)
        assertTrue(tm.canAffordAnotherSliderMove(t0 + 3_500L, isLiveStreamActive = true))
        tm.recordSliderMoveDispatched(420f)
        tm.recordSliderMoveDispatched(360f)
        tm.recordSliderMoveDispatched(330f)
        assertEquals(4, tm.sliderMovesThisTurn)

        // Frame 3: coin2 temporarily missed by glare -> consensus fusion still preserves coin2 AND averages coin1
        tm.recordObservation(t0 + 4_000L, listOf(coin1Frame1), striker1.copy(center = com.example.game.Point2D(310f, 1320f)), com.example.game.Point2D(310f, 1600f), null, 0.94f)
        val fused = tm.buildConsensusPieces(listOf(coin1Frame1), coinRadiusPx = 28f)
        assertEquals(2, fused.size)
        val fusedWhite = fused.first { it.color == com.example.game.CoinColor.WHITE }
        assertTrue("Expected stabilized X between 300 and 304, got ${fusedWhite.center.x}", fusedWhite.center.x in 300.1f..303.5f)

        // Still within 7s live-stream reserve at 5.8s, but cut off at 6.4s (live) and 5.2s (non-live)
        assertTrue(tm.canAffordAnotherSliderMove(t0 + 5_800L, isLiveStreamActive = true))
        assertFalse(tm.canAffordAnotherSliderMove(t0 + 6_400L, isLiveStreamActive = true))
        assertFalse(tm.canAffordAnotherSliderMove(t0 + 5_200L, isLiveStreamActive = false))

        // Hard 6-move cap blocks a 7th slide even early in the turn
        tm.recordSliderMoveDispatched(300f)
        tm.recordSliderMoveDispatched(280f)
        assertEquals(6, tm.sliderMovesThisTurn)
        assertFalse(tm.canAffordAnotherSliderMove(t0 + 4_500L, isLiveStreamActive = true))

        tm.resetTurn()
        assertFalse(tm.isTurnActive)
        assertEquals(0, tm.sliderMovesThisTurn)
    }

    @Test
    fun `freestyle carrom pots white black and red freely and only treats striker scratch as foul`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        // Only BLACK coin positioned cleanly in front of Top-Right pocket
        val blackTarget = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(790f, 680f),
            radiusPx = 28f
        )
        // White coin tucked away on bottom side (hard cut), so Black coin is the clear Freestyle Sure Shot
        val distantWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(460f, 940f),
            radiusPx = 28f
        )

        val sol = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(blackTarget, distantWhite, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            shotMemory = null,
            usePhysicsRollout = true,
            isFreestyle = true
        )
        org.junit.Assert.assertNotNull(sol)
        assertEquals(com.example.game.CoinColor.BLACK, sol!!.targetCoin.color)
        assertTrue(sol.isSureShot)
        assertFalse(sol.rejectedDueToCollisionOrFoul)

        // Memory evaluation in Freestyle: potting a black coin counts as clean pot, NOT opponent foul
        val preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        val shotRecord = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.BLACK,
            pocketIndex = 1,
            cutAngleDeg = 18f,
            strikerNormalizedX = 0.5f,
            powerStyle = com.example.game.CarromPowerStyle.MEDIUM,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = preCounts,
            isFreestyle = true
        )
        val blackPotEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shotRecord,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 4, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.POCKETED_MY_COIN, blackPotEval.outcomeType)

        // Only striker scratch (total coin count increasing due to penalty coin return) marks FOUL in Freestyle
        val scratchEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shotRecord,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 6, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY, scratchEval.outcomeType)

        // Default AgentSettings also defaults to Freestyle mode
        assertEquals(com.example.agent.CarromRuleMode.FREESTYLE, com.example.agent.AgentSettings().carromRuleMode)
    }

    @Test
    fun `built in carrom playbook builds instantly and avoids cycle zero research stall`() {
        val freestylePb = com.example.agent.CarromContextHelper.buildDefaultCarromPlaybook(
            com.example.agent.CarromRuleMode.FREESTYLE
        )
        assertEquals("carrom_board_game", freestylePb.topicKey)
        assertTrue(freestylePb.topicTitle.contains("Freestyle", ignoreCase = true))
        assertTrue(freestylePb.rulesSummary.contains("White, Black, and Red", ignoreCase = true))
        assertTrue(freestylePb.foulLessons.any { it.contains("Striker falling into any pocket", ignoreCase = true) })
        assertTrue(freestylePb.winningStrategies.size >= 4)

        val presetGoal = "Open Carrom game, research board rules and foul conditions first, position the striker with DRAG, aim opposite the pocket to pot my coins cleanly, evaluate each shot outcome, and save winning angles & foul mistakes in my playbook."
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = presetGoal,
                packageName = "",
                playbookTopicKey = null
            )
        )

        val lobbyNodes = listOf(
            ScreenNode(
                id = 1,
                className = "android.widget.Button",
                text = "Freestyle",
                contentDesc = "",
                viewId = "btn_freestyle",
                bounds = ScreenBounds(200, 800, 600, 900),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isChecked = false,
                isFocused = false
            )
        )
        assertTrue(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(lobbyNodes))
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(emptyList()))
    }

    @Test
    fun `physics simulator rejects Sure Shot when striker scratches and safest break stays foul free`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 400f,
            right = 1020f,
            bottom = 1360f,
            pockets = listOf(
                com.example.game.Point2D(120f, 460f),
                com.example.game.Point2D(960f, 460f),
                com.example.game.Point2D(120f, 1300f),
                com.example.game.Point2D(960f, 1300f)
            ),
            baselineY = 1210f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1210f),
            radiusPx = 35f
        )
        // Coin right on the straight diagonal line into Top-Right pocket -> hard straight follow scratches striker
        val coinNearPocket = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(820f, 650f),
            radiusPx = 28f
        )
        val dirToPocket = (board.pockets[1] - striker.center).normalized()
        val scratchCandidate = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = dirToPocket,
            ghostBall = coinNearPocket.center - dirToPocket * 63f,
            targetCoin = coinNearPocket,
            targetPocket = board.pockets[1],
            pocketIndex = 1,
            cutAngleDeg = 0f,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = 1.35f,
            staticScore = 0.85f,
            totalDistance = (board.pockets[1] - striker.center).length()
        )

        val simRes = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = listOf(coinNearPocket, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(scratchCandidate),
            shotMemory = null,
            matchCalibration = null,
            isFreestyle = true
        )
        // Even if evaluated, a scratching shot or risky neighbor must never pass as a Sure Shot
        if (simRes != null) {
            assertFalse(simRes.second.isSureShot && simRes.second.predictedScratchFoul)
        }

        // Safely breaks a central cluster in Freestyle with zero predicted striker scratch
        val centerCluster = listOf(
            com.example.game.CarromPiece(com.example.game.CoinColor.QUEEN, com.example.game.Point2D(540f, 880f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(482f, 880f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(598f, 880f), 28f),
            striker
        )
        val safeBreak = com.example.game.CarromGeometrySolver.solveSafestBreakOrPotShot(
            board = board,
            pieces = centerCluster,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            shotMemory = null,
            usePhysicsRollout = true,
            isFreestyle = true
        )
        org.junit.Assert.assertNotNull(safeBreak)
        assertFalse(safeBreak!!.rejectedDueToCollisionOrFoul)
    }

    @Test
    fun `launcher search helper navigates home to app drawer searches exact app name and detects wrong app`() {
        fun node(
            id: Int,
            text: String,
            desc: String = "",
            viewId: String = "",
            clickable: Boolean = true,
            editable: Boolean = false,
            top: Int = 400,
            bottom: Int = 560,
            left: Int = 100,
            right: Int = 300
        ) = ScreenNode(
            id = id,
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            text = text,
            contentDesc = desc,
            viewId = viewId,
            bounds = ScreenBounds(left, top, right, bottom),
            isClickable = clickable,
            isEditable = editable,
            isScrollable = false,
            isChecked = false,
            isFocused = editable
        )

        // Extracts canonical Samsung One UI app labels from English & Bengali prompts
        assertEquals("Lite", com.example.agent.LauncherSearchHelper.extractTargetAppName("Facebook Lite এ গিয়ে মেসেজ দেখো"))
        assertEquals("WhatsApp", com.example.agent.LauncherSearchHelper.extractTargetAppName("হোয়াটসঅ্যাপ এ গিয়ে রিয়াদকে হাই দাও"))
        assertEquals("Nagad", com.example.agent.LauncherSearchHelper.extractTargetAppName("Nagad এ্যাপ ওপেন করো"))
        assertEquals("YouTube", com.example.agent.LauncherSearchHelper.extractTargetAppName("Open YouTube and scroll videos"))

        // Step 1: On Home screen -> swipes up to open App Drawer
        val homeSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.sec.android.app.launcher",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(node(1, "Phone", top = 1900, bottom = 2100)),
            screenshot = null,
            captureNote = ""
        )
        val step1 = com.example.agent.LauncherSearchHelper.planNextLauncherSearchStep(
            goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
            snapshot = homeSnapshot,
            alreadyOpenedDrawer = false,
            alreadyTappedSearch = false,
            alreadyTypedSearch = false
        )
        org.junit.Assert.assertNotNull(step1)
        assertEquals(AgentActionType.DRAG, step1!!.action.actionType)
        assertEquals("OPEN_APP_DRAWER", step1.stageTag)

        // Step 2: On One UI App Drawer screenshot (with Search pill at bottom) -> taps Search bar
        val drawerSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.sec.android.app.launcher",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                node(1, "My Galaxy", top = 280, bottom = 460),
                node(2, "Lite", top = 280, bottom = 460),
                node(3, "WhatsApp", top = 620, bottom = 800),
                node(4, "Nagad", top = 1320, bottom = 1500),
                node(5, "Search", viewId = "app_search_bar", top = 2140, bottom = 2280)
            ),
            screenshot = null,
            captureNote = ""
        )
        val step2 = com.example.agent.LauncherSearchHelper.planNextLauncherSearchStep(
            goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
            snapshot = drawerSnapshot,
            alreadyOpenedDrawer = true,
            alreadyTappedSearch = false,
            alreadyTypedSearch = false
        )
        org.junit.Assert.assertNotNull(step2)
        assertEquals(AgentActionType.TAP, step2!!.action.actionType)
        assertEquals(5, step2.action.targetNodeId)
        assertEquals("TAP_SEARCH_BAR", step2.stageTag)

        // Step 3: Search input box open -> types exact app name "Lite"
        val finderInputSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.samsung.android.app.galaxyfinder",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                node(10, "Search", viewId = "search_src_text", clickable = true, editable = true, top = 120, bottom = 220)
            ),
            screenshot = null,
            captureNote = ""
        )
        val step3 = com.example.agent.LauncherSearchHelper.planNextLauncherSearchStep(
            goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
            snapshot = finderInputSnapshot,
            alreadyOpenedDrawer = true,
            alreadyTappedSearch = true,
            alreadyTypedSearch = false
        )
        org.junit.Assert.assertNotNull(step3)
        assertEquals(AgentActionType.INPUT_TEXT, step3!!.action.actionType)
        assertEquals("Lite", step3.action.textToType)
        assertEquals("TYPE_APP_NAME", step3.stageTag)

        // Step 4: Search results visible -> taps exact matching app "Lite"
        val finderResultsSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.samsung.android.app.galaxyfinder",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                node(10, "Lite", viewId = "search_src_text", clickable = true, editable = true, top = 120, bottom = 220),
                node(11, "Lite", viewId = "app_icon_title", clickable = true, editable = false, top = 360, bottom = 520)
            ),
            screenshot = null,
            captureNote = ""
        )
        val step4 = com.example.agent.LauncherSearchHelper.planNextLauncherSearchStep(
            goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
            snapshot = finderResultsSnapshot,
            alreadyOpenedDrawer = true,
            alreadyTappedSearch = true,
            alreadyTypedSearch = true
        )
        org.junit.Assert.assertNotNull(step4)
        assertEquals(AgentActionType.TAP, step4!!.action.actionType)
        assertEquals(11, step4.action.targetNodeId)
        assertEquals("TAP_APP_RESULT", step4.stageTag)

        // Wrong app detection: landed in WhatsApp instead of Lite -> true; landed in com.facebook.lite -> false
        assertTrue(
            com.example.agent.LauncherSearchHelper.didLandInWrongApp(
                goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
                currentPackage = "com.whatsapp",
                selfPackage = "com.example"
            )
        )
        assertFalse(
            com.example.agent.LauncherSearchHelper.didLandInWrongApp(
                goal = "Facebook Lite এ গিয়ে নিউজফিড স্ক্রল করো",
                currentPackage = "com.facebook.lite",
                selfPackage = "com.example"
            )
        )
    }

    @Test
    fun `action enum supports SWIPE_LEFT and SWIPE_RIGHT and reverse navigation detection`() {
        assertEquals(AgentActionType.SWIPE_LEFT, AgentActionType.fromWire("SWIPE_LEFT"))
        assertEquals(AgentActionType.SWIPE_RIGHT, AgentActionType.fromWire("swipe_right"))
        assertTrue(
            com.example.agent.ActionCacheStore.isReverseNavigationAction(
                AgentActionType.SWIPE_LEFT,
                AgentActionType.SWIPE_RIGHT
            )
        )
        assertTrue(
            com.example.agent.ActionCacheStore.isReverseNavigationAction(
                AgentActionType.SCROLL_DOWN,
                AgentActionType.SCROLL_UP
            )
        )
        assertFalse(
            com.example.agent.ActionCacheStore.isReverseNavigationAction(
                AgentActionType.SWIPE_LEFT,
                AgentActionType.SCROLL_DOWN
            )
        )
    }

    @Test
    fun `wa business relationship platform matches com whatsapp w4b and isolates from personal whatsapp`() {
        val clientWab = com.example.agent.ContactProfile(
            id = "wb1",
            name = "Arif Client",
            platform = com.example.agent.ChatPlatform.WHATSAPP_BUSINESS,
            relationship = com.example.agent.RelationshipType.BUSINESS,
            customStyleNote = "Quote wholesale rate",
            autoReplyEnabled = true,
            createdAtMs = 4000L
        )
        val personalFriend = com.example.agent.ContactProfile(
            id = "wa1",
            name = "Arif Client",
            platform = com.example.agent.ChatPlatform.WHATSAPP,
            relationship = com.example.agent.RelationshipType.FRIEND,
            customStyleNote = "Casual friend tone",
            autoReplyEnabled = true,
            createdAtMs = 3000L
        )
        val contacts = listOf(clientWab, personalFriend)

        val matchedW4b = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Arif Client",
            packageName = "com.whatsapp.w4b",
            goal = "WA Business এ ক্লায়েন্টকে রিপ্লাই দাও",
            recentNotifications = emptyList()
        )
        assertEquals("wb1", matchedW4b?.id)
        assertEquals(com.example.agent.RelationshipType.BUSINESS, matchedW4b?.relationship)

        val matchedPersonal = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = contacts,
            peerName = "Arif Client",
            packageName = "com.whatsapp",
            goal = "WhatsApp এ রিপ্লাই দাও",
            recentNotifications = emptyList()
        )
        assertEquals("wa1", matchedPersonal?.id)
        assertEquals(com.example.agent.RelationshipType.FRIEND, matchedPersonal?.relationship)

        assertTrue(com.example.agent.ChatPlatform.BOTH.matchesPackage("com.whatsapp.w4b"))
        assertEquals("WA Business", com.example.agent.LauncherSearchHelper.extractTargetAppName("WA Business এ গিয়ে মেসেজ দেখো"))
        assertEquals("WA Business", com.example.agent.LauncherSearchHelper.extractTargetAppName("হোয়াটসঅ্যাপ বিজনেস ওপেন করো"))
        assertTrue(
            com.example.agent.LauncherSearchHelper.didLandInWrongApp(
                goal = "WA Business এ গিয়ে ক্লায়েন্টকে রিপ্লাই দাও",
                currentPackage = "com.whatsapp",
                selfPackage = "com.example"
            )
        )
        assertFalse(
            com.example.agent.LauncherSearchHelper.didLandInWrongApp(
                goal = "WA Business এ গিয়ে ক্লায়েন্টকে রিপ্লাই দাও",
                currentPackage = "com.whatsapp.w4b",
                selfPackage = "com.example"
            )
        )
    }

    @Test
    fun `notification auto reply filters noise extracts specific sender ID and checks relationship first`() {
        val groupSummary = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.whatsapp",
            title = "WhatsApp",
            body = "2 new messages from 2 chats",
            conversationTitle = null,
            rawFallbackText = "WhatsApp: 2 new messages from 2 chats",
            timestampMs = 10_000L,
            isGroupSummary = true
        )
        org.junit.Assert.assertNull(groupSummary)

        val syncingNotif = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.whatsapp.w4b",
            title = "WA Business",
            body = "Checking for new messages",
            conversationTitle = null,
            rawFallbackText = "Checking for new messages",
            timestampMs = 10_100L,
            isGroupSummary = false
        )
        org.junit.Assert.assertNull(syncingNotif)

        val realWab = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.whatsapp.w4b",
            title = "Arif Client (2 messages)",
            body = "Vai 100 piece er dam koto?",
            conversationTitle = null,
            rawFallbackText = "",
            timestampMs = 12_000L,
            isGroupSummary = false
        )
        org.junit.Assert.assertNotNull(realWab)
        assertEquals("Arif Client", realWab!!.senderName)
        assertEquals("Vai 100 piece er dam koto?", realWab.messageBody)

        val targets = com.example.agent.NotificationReplyHelper.extractNewTargets(
            notifications = listOf(realWab),
            minTimestampMs = 10_000L,
            handledNotificationKeys = emptySet(),
            alreadyQueuedOrActive = emptyList()
        )
        assertEquals(1, targets.size)
        assertEquals("WA Business", targets.first().appDisplayName)
        assertEquals("Arif Client", targets.first().senderName)
        assertEquals("WA Business", com.example.agent.NotificationReplyHelper.appSearchNameForPackage("com.whatsapp.w4b"))
        assertEquals("WhatsApp", com.example.agent.NotificationReplyHelper.appSearchNameForPackage("com.whatsapp"))

        val savedClient = com.example.agent.ContactProfile(
            id = "c_wab",
            name = "Arif Client",
            platform = com.example.agent.ChatPlatform.WHATSAPP_BUSINESS,
            relationship = com.example.agent.RelationshipType.BUSINESS,
            customStyleNote = "Quote wholesale rate",
            autoReplyEnabled = true,
            createdAtMs = 5000L
        )
        val savedMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = listOf(savedClient),
            peerName = targets.first().senderName,
            packageName = targets.first().packageName,
            goal = "auto মেসেজ আসলে রিপ্লাই দিবে",
            recentNotifications = listOf(realWab)
        )
        val savedSummary = com.example.agent.NotificationReplyHelper.buildRelationshipCheckSummary(
            target = targets.first(),
            matchedContact = savedMatch
        )
        assertTrue(savedSummary.contains("Business"))
        assertTrue(savedSummary.contains("Quote wholesale rate"))

        val unknownTarget = targets.first().copy(senderName = "Unknown Buyer")
        val unsavedMatch = com.example.agent.ContactProfileStore.matchContactFromList(
            contacts = listOf(savedClient),
            peerName = unknownTarget.senderName,
            packageName = unknownTarget.packageName,
            goal = "auto মেসেজ আসলে রিপ্লাই দিবে",
            recentNotifications = emptyList()
        )
        org.junit.Assert.assertNull(unsavedMatch)
        val politeSummary = com.example.agent.NotificationReplyHelper.buildRelationshipCheckSummary(
            target = unknownTarget,
            matchedContact = null
        )
        assertTrue(politeSummary.contains("No relationship saved"))
        assertTrue(politeSummary.contains("সুন্দর ও ভদ্রভাবে কথা বলো"))

        val defaultSettings = AgentSettings(replyOnlyOnMessageNotifications = true)
        assertTrue(
            com.example.agent.NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
                goal = "auto মেসেজ আসলে রিপ্লাই দিবে শুধু নোটিফিকেশনে",
                mode = com.example.agent.AgentExecutionMode.CONTINUOUS_MONITOR,
                settings = defaultSettings
            )
        )
        assertFalse(
            com.example.agent.NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
                goal = "Open Carrom and play freestyle",
                mode = com.example.agent.AgentExecutionMode.CONTINUOUS_MONITOR,
                settings = defaultSettings
            )
        )
        assertFalse(
            com.example.agent.NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
                goal = "auto মেসেজ আসলে রিপ্লাই দিবে",
                mode = com.example.agent.AgentExecutionMode.CONTINUOUS_MONITOR,
                settings = AgentSettings(replyOnlyOnMessageNotifications = false)
            )
        )

        assertTrue(com.example.agent.NotificationReplyHelper.matchesTargetPeer("Arif Client", "Arif Client"))
        assertFalse(com.example.agent.NotificationReplyHelper.matchesTargetPeer("Karim Vai", "Arif Client"))
    }

    @Test
    fun `action reflector detects rejected input text and guards incomplete sub goal checklists`() {
        assertTrue(com.example.agent.ActionReflectorHelper.hasIncompleteChecklist("[x] Open App | [ ] Send Reply"))
        assertFalse(com.example.agent.ActionReflectorHelper.hasIncompleteChecklist("[x] Open App | [x] Send Reply"))
        assertFalse(com.example.agent.ActionReflectorHelper.hasIncompleteChecklist(null))

        val emptySearchSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.whatsapp",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                ScreenNode(
                    id = 1,
                    className = "android.widget.EditText",
                    text = "Search...",
                    contentDesc = "",
                    viewId = "search_bar",
                    bounds = ScreenBounds(80, 120, 960, 220),
                    isClickable = true,
                    isEditable = true,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = false
                )
            ),
            screenshot = null,
            captureNote = ""
        )
        val missingInputWarning = com.example.agent.ActionReflectorHelper.buildReflectionHint(
            prevActionType = AgentActionType.INPUT_TEXT,
            prevPackageName = "com.whatsapp",
            prevInputText = "Arif Client",
            consecutiveNoChangeCount = 0,
            snapshot = emptySearchSnapshot,
            isCarromContext = false
        )
        assertTrue(missingInputWarning.contains("REFLECTOR WARNING"))
        assertTrue(missingInputWarning.contains("Arif Client"))

        val filledSearchSnapshot = emptySearchSnapshot.copy(
            nodes = listOf(
                ScreenNode(
                    id = 1,
                    className = "android.widget.EditText",
                    text = "Arif Client",
                    contentDesc = "",
                    viewId = "search_bar",
                    bounds = ScreenBounds(80, 120, 960, 220),
                    isClickable = true,
                    isEditable = true,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = true
                )
            )
        )
        val okInput = com.example.agent.ActionReflectorHelper.buildReflectionHint(
            prevActionType = AgentActionType.INPUT_TEXT,
            prevPackageName = "com.whatsapp",
            prevInputText = "Arif Client",
            consecutiveNoChangeCount = 0,
            snapshot = filledSearchSnapshot,
            isCarromContext = false
        )
        assertTrue(okInput.contains("REFLECTOR OK"))

        val staleTapWarning = com.example.agent.ActionReflectorHelper.buildReflectionHint(
            prevActionType = AgentActionType.TAP,
            prevPackageName = "com.whatsapp",
            prevInputText = null,
            consecutiveNoChangeCount = 2,
            snapshot = filledSearchSnapshot,
            isCarromContext = false
        )
        assertTrue(staleTapWarning.contains("REFLECTOR WARNING"))
        assertTrue(staleTapWarning.contains("2x"))
    }

    @Test
    fun `all platform background goal accepts any non system app notification and talks simply if unsaved`() {
        val bgGoal = com.example.agent.NotificationReplyHelper.ALL_PLATFORM_BG_GOAL
        assertTrue(com.example.agent.NotificationReplyHelper.isAllPlatformAutoReplyGoal(bgGoal))
        assertTrue(
            com.example.agent.NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
                goal = bgGoal,
                mode = com.example.agent.AgentExecutionMode.CONTINUOUS_MONITOR,
                settings = AgentSettings(replyOnlyOnMessageNotifications = false)
            )
        )

        val discordNotif = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.discord",
            title = "Tanvir",
            body = "Bhai ajke game khelba?",
            conversationTitle = null,
            rawFallbackText = "",
            timestampMs = 50_000L,
            isGroupSummary = false
        )
        val signalNotif = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "org.thoughtcrime.securesms",
            title = "Nabila",
            body = "Kothay acho?",
            conversationTitle = null,
            rawFallbackText = "",
            timestampMs = 51_000L,
            isGroupSummary = false
        )
        val customAppNotif = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.custom.chatapp",
            title = "Rafi",
            body = "Hello there!",
            conversationTitle = null,
            rawFallbackText = "",
            timestampMs = 52_000L,
            isGroupSummary = false
        )
        val systemNotif = com.example.agent.NotificationReplyHelper.parseNotification(
            packageName = "com.android.systemui",
            title = "Android System",
            body = "Charging rapidly",
            conversationTitle = null,
            rawFallbackText = "",
            timestampMs = 53_000L,
            isGroupSummary = false
        )
        org.junit.Assert.assertNull(systemNotif)
        org.junit.Assert.assertNotNull(discordNotif)
        org.junit.Assert.assertNotNull(signalNotif)
        org.junit.Assert.assertNotNull(customAppNotif)

        val targets = com.example.agent.NotificationReplyHelper.extractNewTargets(
            notifications = listOfNotNull(discordNotif, signalNotif, customAppNotif),
            minTimestampMs = 40_000L,
            handledNotificationKeys = emptySet(),
            alreadyQueuedOrActive = emptyList(),
            allowAnyApp = true,
            packageLabelResolver = { pkg ->
                if (pkg == "com.custom.chatapp") "CustomChat" else null
            }
        )
        assertEquals(3, targets.size)
        assertEquals("Discord", targets[0].appDisplayName)
        assertEquals("Signal", targets[1].appDisplayName)
        assertEquals("CustomChat", targets[2].appDisplayName)

        // With allowAnyApp = true, even without saved relationship, talks simply & naturally
        val simpleSummary = com.example.agent.NotificationReplyHelper.buildRelationshipCheckSummary(
            target = targets[2],
            matchedContact = null
        )
        assertTrue(simpleSummary.contains("সিম্পল, স্বাভাবিক"))
        assertTrue(simpleSummary.contains("Keep talking simply"))

        // ChatTurnGuard treats custom app as a valid chat screen when forceTreatAsMessaging is true
        val customSnapshot = com.example.agent.UiSnapshot(
            packageName = "com.custom.chatapp",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                ScreenNode(
                    id = 1,
                    className = "android.widget.TextView",
                    text = "Rafi",
                    contentDesc = "",
                    viewId = "header_name",
                    bounds = ScreenBounds(120, 120, 500, 190),
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = false
                ),
                ScreenNode(
                    id = 2,
                    className = "android.widget.TextView",
                    text = "Hello there!",
                    contentDesc = "",
                    viewId = "msg_body",
                    bounds = ScreenBounds(80, 900, 620, 980),
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = false
                ),
                ScreenNode(
                    id = 3,
                    className = "android.widget.EditText",
                    text = "Write a message",
                    contentDesc = "",
                    viewId = "msg_input",
                    bounds = ScreenBounds(80, 2100, 880, 2220),
                    isClickable = true,
                    isEditable = true,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = true
                )
            ),
            screenshot = null,
            captureNote = ""
        )
        val obs = com.example.agent.ChatTurnGuard.extractChatObservation(
            snapshot = customSnapshot,
            forceTreatAsMessaging = true
        )
        assertTrue(obs.isMessagingApp)
        assertTrue(obs.inChatThread)
        assertEquals("Rafi", obs.peerName)
        assertEquals("Hello there!", obs.lastMessageText)
        assertFalse(AgentSettings().showFloatingOverlay)
    }

    @Test
    fun `chat turn guard distinguishes ME vs THEM history bubbles and generates human style mirror hint`() {
        fun bubbleNode(id: Int, text: String, top: Int, left: Int, right: Int) = ScreenNode(
            id = id,
            className = "android.widget.TextView",
            text = text,
            contentDesc = "",
            viewId = "msg_text",
            bounds = ScreenBounds(left, top, right, top + 75),
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val snapshotWithHistory = com.example.agent.UiSnapshot(
            packageName = "com.whatsapp",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                ScreenNode(
                    id = 1,
                    className = "android.widget.TextView",
                    text = "Sakib",
                    contentDesc = "",
                    viewId = "conversation_contact_name",
                    bounds = ScreenBounds(140, 120, 420, 185),
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = false
                ),
                // Left-aligned THEM bubble
                bubbleNode(2, "Mama koi tui?", top = 700, left = 60, right = 440),
                // Right-aligned ME bubble
                bubbleNode(3, "Ei to basay re mama, tor ki khobor?", top = 840, left = 420, right = 1020),
                // Left-aligned THEM latest bubble
                bubbleNode(4, "Baire ber hobi naki?", top = 980, left = 60, right = 480),
                ScreenNode(
                    id = 5,
                    className = "android.widget.EditText",
                    text = "Message",
                    contentDesc = "",
                    viewId = "entry",
                    bounds = ScreenBounds(120, 2100, 860, 2220),
                    isClickable = true,
                    isEditable = true,
                    isScrollable = false,
                    isChecked = false,
                    isFocused = true
                )
            ),
            screenshot = null,
            captureNote = ""
        )

        val obs = com.example.agent.ChatTurnGuard.extractChatObservation(snapshotWithHistory)
        assertEquals(3, obs.recentBubbles.size)
        assertEquals("THEM", obs.recentBubbles[0].speaker)
        assertEquals("ME", obs.recentBubbles[1].speaker)
        assertEquals("THEM", obs.recentBubbles[2].speaker)

        val styleHint = com.example.agent.ChatTurnGuard.buildChatStyleHistoryHint(obs)
        assertTrue(styleHint.contains("[ME]: \"Ei to basay re mama, tor ki khobor?\""))
        assertTrue(styleHint.contains("[THEM]: \"Baire ber hobi naki?\""))
        assertTrue(styleHint.contains("Banglish/English"))
        assertTrue(styleHint.contains("NOT an AI"))

        // When no [ME] bubbles are visible yet and thread is sparse, hint allows 1 optional SCROLL_UP
        val sparseObs = obs.copy(
            recentBubbles = listOf(com.example.agent.ChatTurnGuard.ChatBubble("THEM", "কেমন আছেন?")),
            transcriptLines = listOf("কেমন আছেন?")
        )
        val scrollHint = com.example.agent.ChatTurnGuard.buildChatStyleHistoryHint(sparseObs, alreadyScrolledHistory = false)
        assertTrue(scrollHint.contains("SCROLL_UP ONCE"))
        assertTrue(scrollHint.contains("Bangla Unicode"))
    }
}



