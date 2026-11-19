package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentActionType
import com.example.ai.GeminiAiPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TapPilot Agent", appName)
    }

    @Test
    fun `gemini planner parses structured JSON action with waitSeconds and memoryNote`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "actionType": "WAIT",
              "targetNodeId": null,
              "x": null,
              "y": null,
              "textToType": null,
              "waitSeconds": 45,
              "memoryNote": "Posted in group; awaiting Messenger reply.",
              "subGoalChecklist": "[x] Open App | [ ] Verify Reply",
              "reflectionStatus": "OK",
              "reason": "Waiting for seller reply",
              "requiresConfirmation": false
            }
        """.trimIndent()

        val parsed = planner.parsePlannedAction(json)
        assertEquals(AgentActionType.WAIT, parsed.actionType)
        assertEquals(45, parsed.waitSeconds)
        assertEquals("Posted in group; awaiting Messenger reply.", parsed.memoryNote)
        assertEquals("[x] Open App | [ ] Verify Reply", parsed.subGoalChecklist)
        assertEquals("OK", parsed.reflectionStatus)
        assertFalse(parsed.requiresConfirmation)
    }

    @Test
    fun `gemini planner parses DRAG shot with learnedRule and foulOrMistakeLesson`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "actionType": "DRAG",
              "targetNodeId": null,
              "x": 540,
              "y": 1620,
              "endX": 460,
              "endY": 1820,
              "durationMs": 420,
              "textToType": null,
              "waitSeconds": null,
              "memoryNote": "Aiming striker for top-right white coin",
              "learnedRule": "Pull striker slightly left-down to sink top-right pocket coin.",
              "foulOrMistakeLesson": "Avoid pulling straight down when center coin is near pocket edge.",
              "reason": "Pull striker opposite target vector to pocket white coin",
              "requiresConfirmation": false
            }
        """.trimIndent()

        val parsed = planner.parsePlannedAction(json)
        assertEquals(AgentActionType.DRAG, parsed.actionType)
        assertEquals(540, parsed.x)
        assertEquals(1620, parsed.y)
        assertEquals(460, parsed.endX)
        assertEquals(1820, parsed.endY)
        assertEquals(420L, parsed.durationMs)
        assertEquals("Pull striker slightly left-down to sink top-right pocket coin.", parsed.learnedRule)
        assertEquals(
            "Avoid pulling straight down when center coin is near pocket edge.",
            parsed.foulOrMistakeLesson
        )
        assertFalse(parsed.requiresConfirmation)
    }

    @Test
    fun `research playbook parses and serializes cleanly`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "topicKey": "carrom_board_game",
              "topicTitle": "Carrom Board Game",
              "rulesSummary": "Position striker on baseline and drag opposite to shoot coins into 4 corner pockets.",
              "winningStrategies": [
                "Align striker center opposite target coin center toward corner pocket."
              ],
              "foulLessons": [
                "Never pocket the striker alone; causes foul and returns one pocketed coin."
              ]
            }
        """.trimIndent()

        val pb = planner.parseResearchPlaybook(json, "Play Carrom game")
        val encoded = com.example.agent.AgentKnowledgeStore.serializePlaybooksJson(listOf(pb))
        val decoded = com.example.agent.AgentKnowledgeStore.parsePlaybooksJson(encoded)

        assertEquals(1, decoded.size)
        assertEquals("carrom_board_game", decoded[0].topicKey)
        assertEquals("Carrom Board Game", decoded[0].topicTitle)
        assertEquals(1, decoded[0].winningStrategies.size)
        assertEquals(1, decoded[0].foulLessons.size)
    }

    @Test
    fun `action cache serializes and parses entries cleanly`() {
        val entry = com.example.agent.CachedActionEntry(
            cacheKey = "abc1234567",
            goalKeywords = "carrom_classic_play",
            packageName = "com.miniclip.carrom",
            screenSignature = "sig999",
            actionType = AgentActionType.TAP,
            targetLabel = "Play Classic",
            targetViewId = "btn_play",
            tapX = 190,
            tapY = 340,
            textToType = null,
            hitCount = 3,
            lastUsedMs = 123456789L
        )
        val encoded = com.example.agent.ActionCacheStore.serializeJson(listOf(entry))
        val decoded = com.example.agent.ActionCacheStore.parseJson(encoded)

        assertEquals(1, decoded.size)
        assertEquals("btn_play", decoded[0].targetViewId)
        assertEquals(3, decoded[0].hitCount)
        assertEquals(190, decoded[0].tapX)
    }

    @Test
    fun `visual grid annotator preserves dimensions without mutating source bitmap`() {
        val source = android.graphics.Bitmap.createBitmap(540, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.rgb(210, 180, 140))

        val annotated = com.example.ai.VisualGridAnnotator.createAnnotatedCopy(
            source = source,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(540, annotated.width)
        assertEquals(1200, annotated.height)
        assertFalse(annotated === source)
        // Corner pixel at (0,0) of immutable source remains intact while annotated copy draws grid lines
        assertEquals(android.graphics.Color.rgb(210, 180, 140), source.getPixel(108, 600))
        annotated.recycle()
        source.recycle()
    }

    @Test
    fun `set of mark annotator draws numbered bounding boxes without mutating source bitmap`() {
        val source = android.graphics.Bitmap.createBitmap(540, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.WHITE)

        val nodes = listOf(
            com.example.agent.ScreenNode(
                id = 1,
                className = "android.widget.EditText",
                text = "Search",
                contentDesc = "",
                viewId = "search_input",
                bounds = com.example.agent.ScreenBounds(60, 140, 980, 260),
                isClickable = true,
                isEditable = true,
                isScrollable = false,
                isChecked = false,
                isFocused = false
            ),
            com.example.agent.ScreenNode(
                id = 2,
                className = "android.widget.Button",
                text = "Send",
                contentDesc = "Send",
                viewId = "btn_send",
                bounds = com.example.agent.ScreenBounds(840, 1900, 1020, 2040),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isChecked = false,
                isFocused = false
            )
        )

        val som = com.example.ai.VisualGridAnnotator.createSetOfMarkCopy(
            source = source,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = nodes
        )

        assertEquals(540, som.width)
        assertEquals(1200, som.height)
        assertFalse(som === source)
        assertEquals(android.graphics.Color.WHITE, source.getPixel(270, 600))
        som.recycle()
        source.recycle()
    }

    @Test
    fun `carrom vision detector finds painted discs and solves shot offline without AI calls`() {
        com.example.game.CarromVisionDetector.resetMotionHistory()
        val width = 540
        val height = 1000
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        // Warm tan carrom wooden background (neither white, black, red queen, nor cyan striker)
        bm.eraseColor(android.graphics.Color.rgb(205, 165, 110))

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            val minX = (cx - r).toInt().coerceIn(0, width - 1)
            val maxX = (cx + r).toInt().coerceIn(0, width - 1)
            val minY = (cy - r).toInt().coerceIn(0, height - 1)
            val maxY = (cy + r).toInt().coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        val boardLeft = width * 0.06f
        val boardWidth = width * 0.88f
        val boardTop = (height - boardWidth) * 0.48f
        val baselineY = boardTop + boardWidth * 0.855f

        // Draw target white coin aimed comfortably toward top-left corner
        paintDisc(boardLeft + boardWidth * 0.34f, boardTop + boardWidth * 0.36f, 15f, android.graphics.Color.rgb(248, 248, 245))
        // Draw Queen near center
        paintDisc(boardLeft + boardWidth * 0.50f, boardTop + boardWidth * 0.50f, 15f, android.graphics.Color.rgb(225, 28, 35))
        // Draw cyan striker on baseline
        paintDisc(boardLeft + boardWidth * 0.58f, baselineY, 18f, android.graphics.Color.rgb(0, 215, 235))

        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2000,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )

        org.junit.Assert.assertTrue(
            "Expected SolvedShot from local vision detector but got $outcome",
            outcome is com.example.game.CarromVisionOutcome.SolvedShot
        )

        // Sudden large visual change on next frame triggers offline stillness wait (0 API)
        bm.eraseColor(android.graphics.Color.rgb(30, 30, 30))
        val motionOutcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2000,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )
        org.junit.Assert.assertTrue(motionOutcome is com.example.game.CarromVisionOutcome.WaitStillnessOrTurn)
        bm.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `carrom vision detector anchors non-cyan ivory striker using bottom slider knob`() {
        com.example.game.CarromVisionDetector.resetMotionHistory()
        val width = 540
        val height = 1000
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.rgb(212, 174, 122))

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            val minX = (cx - r).toInt().coerceIn(0, width - 1)
            val maxX = (cx + r).toInt().coerceIn(0, width - 1)
            val minY = (cy - r).toInt().coerceIn(0, height - 1)
            val maxY = (cy + r).toInt().coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        val boardLeft = width * 0.055f
        val boardWidth = width * 0.89f
        val boardTop = (height - boardWidth) * 0.48f
        val boardBottom = boardTop + boardWidth
        val baselineY = boardTop + boardWidth * 0.845f
        val strikerX = boardLeft + boardWidth * 0.50f

        // Target white coin + ivory/white striker (not cyan!) + bottom gold slider knob below board
        paintDisc(boardLeft + boardWidth * 0.30f, boardTop + boardWidth * 0.35f, 15f, android.graphics.Color.rgb(248, 248, 245))
        paintDisc(strikerX, baselineY, 19f, android.graphics.Color.rgb(245, 244, 238))
        paintDisc(strikerX, boardBottom + height * 0.06f, 16f, android.graphics.Color.rgb(255, 200, 30))

        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2000,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )

        org.junit.Assert.assertTrue(
            "Expected SolvedShot when ivory striker + bottom slider knob are present, got $outcome",
            outcome is com.example.game.CarromVisionOutcome.SolvedShot
        )
        val solved = outcome as com.example.game.CarromVisionOutcome.SolvedShot
        org.junit.Assert.assertNotNull(solved.detectedStriker)
        org.junit.Assert.assertNotNull(solved.detectedSliderKnob)

        bm.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `contact profile store serializes and parses platform and relationship json cleanly`() {
        val contacts = listOf(
            com.example.agent.ContactProfile(
                id = "c1",
                name = "Rahim",
                platform = com.example.agent.ChatPlatform.FACEBOOK_MESSENGER,
                relationship = com.example.agent.RelationshipType.FRIEND,
                customStyleNote = "Call him mama",
                autoReplyEnabled = true,
                createdAtMs = 1000L
            ),
            com.example.agent.ContactProfile(
                id = "c2",
                name = "Sara",
                platform = com.example.agent.ChatPlatform.WHATSAPP,
                relationship = com.example.agent.RelationshipType.GF,
                customStyleNote = "Warm sweet tone",
                autoReplyEnabled = true,
                createdAtMs = 2000L
            )
        )
        val encoded = com.example.agent.ContactProfileStore.serializeJson(contacts)
        val decoded = com.example.agent.ContactProfileStore.parseJson(encoded)

        assertEquals(2, decoded.size)
        assertEquals("Rahim", decoded[0].name)
        assertEquals(com.example.agent.ChatPlatform.FACEBOOK_MESSENGER, decoded[0].platform)
        assertEquals(com.example.agent.RelationshipType.FRIEND, decoded[0].relationship)
        assertEquals("Sara", decoded[1].name)
        assertEquals(com.example.agent.ChatPlatform.WHATSAPP, decoded[1].platform)
        assertEquals(com.example.agent.RelationshipType.GF, decoded[1].relationship)
    }

    @Test
    fun `carrom shot memory serializes and parses pocket power and placement stats cleanly`() {
        val initial = com.example.game.CarromMemoryData()
        val shot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 1,
            cutAngleDeg = 10f,
            powerStyle = com.example.game.CarromPowerStyle.HARD,
            movedStrikerWithSlider = true,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        )
        val eval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = initial,
            pendingShot = shot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 6, opponentCount = 5, queenCount = 1)
        )

        val encoded = com.example.game.CarromShotMemoryStore.serializeJson(eval.updatedMemory)
        val decoded = com.example.game.CarromShotMemoryStore.parseJson(encoded)

        assertEquals(1, decoded.straightFoulCount)
        assertEquals(1, decoded.placementStats.movedAttempts)
        assertEquals(1, decoded.placementStats.movedFouls)
        assertEquals(1, decoded.recentFouls.size)
        org.junit.Assert.assertTrue(decoded.learnedPowerScaleFor(1, 10f) < 1.0f)
        org.junit.Assert.assertTrue(decoded.matchCalibration.pocketJawSafetyBuffer > 0f)
        org.junit.Assert.assertTrue(decoded.scratchHeatmap.riskFor(1, 10f, 0.5f) > 0.2f)
        org.junit.Assert.assertTrue(decoded.formatForPrompt().contains("Top-Right Pocket"))
    }

    @Test
    fun `carrom vision detector locks shot origin to actual striker when allowStrikerRepositioning is false`() {
        com.example.game.CarromVisionDetector.resetMotionHistory()
        val width = 540
        val height = 1000
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.rgb(212, 174, 122))

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            val minX = (cx - r).toInt().coerceIn(0, width - 1)
            val maxX = (cx + r).toInt().coerceIn(0, width - 1)
            val minY = (cy - r).toInt().coerceIn(0, height - 1)
            val maxY = (cy + r).toInt().coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        val boardLeft = width * 0.055f
        val boardWidth = width * 0.89f
        val boardTop = (height - boardWidth) * 0.48f
        val boardBottom = boardTop + boardWidth
        val baselineY = boardTop + boardWidth * 0.845f
        val strikerBmX = boardLeft + boardWidth * 0.68f // Off-center striker

        paintDisc(boardLeft + boardWidth * 0.28f, boardTop + boardWidth * 0.34f, 15f, android.graphics.Color.rgb(248, 248, 245))
        paintDisc(strikerBmX, baselineY, 19f, android.graphics.Color.rgb(0, 215, 235))
        paintDisc(strikerBmX, boardBottom + height * 0.06f, 16f, android.graphics.Color.rgb(255, 200, 30))

        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2000,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0,
            allowStrikerRepositioning = false
        )

        org.junit.Assert.assertTrue(outcome is com.example.game.CarromVisionOutcome.SolvedShot)
        val solved = outcome as com.example.game.CarromVisionOutcome.SolvedShot
        val striker = solved.detectedStriker
        org.junit.Assert.assertNotNull(striker)
        org.junit.Assert.assertNotNull(solved.detectedSliderKnob)
        assertEquals(striker!!.center.x, solved.solution.strikerPos.x, 4f)
        assertEquals(striker.center.y, solved.solution.strikerPos.y, 4f)

        bm.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `carrom vision detector shoots for custom patterned brown striker and brown slider bar like disc pool screenshot`() {
        com.example.game.CarromVisionDetector.resetMotionHistory()
        val width = 540
        val height = 1200
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        // Dark-red outer background like Carrom Disc Pool
        bm.eraseColor(android.graphics.Color.rgb(92, 24, 18))

        fun paintRect(left: Float, top: Float, right: Float, bottom: Float, colorInt: Int) {
            val l = left.toInt().coerceIn(0, width - 1)
            val t = top.toInt().coerceIn(0, height - 1)
            val r = right.toInt().coerceIn(0, width - 1)
            val b = bottom.toInt().coerceIn(0, height - 1)
            for (y in t..b) {
                for (x in l..r) {
                    bm.setPixel(x, y, colorInt)
                }
            }
        }

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            val minX = (cx - r).toInt().coerceIn(0, width - 1)
            val maxX = (cx + r).toInt().coerceIn(0, width - 1)
            val minY = (cy - r).toInt().coerceIn(0, height - 1)
            val maxY = (cy + r).toInt().coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        val boardLeft = width * 0.055f
        val boardWidth = width * 0.89f
        val boardRight = boardLeft + boardWidth
        val boardTop = (height - boardWidth) * 0.48f
        val boardBottom = boardTop + boardWidth
        val baselineY = boardTop + boardWidth * 0.845f

        // Warm tan board interior
        paintRect(boardLeft, boardTop, boardRight, boardBottom, android.graphics.Color.rgb(212, 170, 128))
        // Stationary small orange baseline end-circles
        paintDisc(boardLeft + boardWidth * 0.175f, baselineY, 12f, android.graphics.Color.rgb(214, 122, 26))
        paintDisc(boardLeft + boardWidth * 0.825f, baselineY, 12f, android.graphics.Color.rgb(214, 122, 26))

        // Touching opening break rack in board center (Red Queen + 6 touching white + 6 outer black)
        val cx = boardLeft + boardWidth * 0.50f
        val cy = boardTop + boardWidth * 0.50f
        paintDisc(cx, cy, 14f, android.graphics.Color.rgb(210, 26, 30))
        val ringAngles = listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)
        for (deg in ringAngles) {
            val rad = Math.toRadians(deg)
            val wx = cx + (27f * kotlin.math.cos(rad)).toFloat()
            val wy = cy + (27f * kotlin.math.sin(rad)).toFloat()
            paintDisc(wx, wy, 14f, android.graphics.Color.rgb(235, 228, 212))
            val bx = cx + (52f * kotlin.math.cos(rad + 0.52)).toFloat()
            val by = cy + (52f * kotlin.math.sin(rad + 0.52)).toFloat()
            paintDisc(bx, by, 14f, android.graphics.Color.rgb(24, 24, 26))
        }

        // Custom patterned dark-brown/silver/maroon monster striker on bottom baseline (NO cyan/gold)
        val strikerX = boardLeft + boardWidth * 0.50f
        paintDisc(strikerX, baselineY, 21f, android.graphics.Color.rgb(74, 42, 26))
        paintDisc(strikerX, baselineY + 3f, 9f, android.graphics.Color.rgb(165, 36, 44))
        paintDisc(strikerX, baselineY - 5f, 6f, android.graphics.Color.rgb(190, 185, 175))

        // Horizontal brown slider bar below board with matching brown/maroon knob at strikerX
        val sliderY = boardBottom + height * 0.145f
        paintRect(
            boardLeft + boardWidth * 0.16f,
            sliderY - 11f,
            boardLeft + boardWidth * 0.84f,
            sliderY + 11f,
            android.graphics.Color.rgb(142, 92, 54)
        )
        paintDisc(strikerX, sliderY, 14f, android.graphics.Color.rgb(58, 30, 18))
        paintDisc(strikerX, sliderY + 2f, 6f, android.graphics.Color.rgb(175, 42, 48))

        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2400,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )

        org.junit.Assert.assertTrue(
            "Expected SolvedShot even with custom brown/patterned striker & brown slider bar, got $outcome",
            outcome is com.example.game.CarromVisionOutcome.SolvedShot
        )
        val solved = outcome as com.example.game.CarromVisionOutcome.SolvedShot
        org.junit.Assert.assertNotNull(solved.detectedStriker)
        org.junit.Assert.assertNotNull(solved.detectedSliderKnob)
        assertEquals(540f, solved.detectedStriker!!.center.x, 25f)

        bm.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `carrom vision detector does not shoot when striker is on opponent top side and bottom baseline is empty`() {
        com.example.game.CarromVisionDetector.resetMotionHistory()
        val width = 540
        val height = 1200
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.rgb(92, 24, 18))

        fun paintRect(left: Float, top: Float, right: Float, bottom: Float, colorInt: Int) {
            val l = left.toInt().coerceIn(0, width - 1)
            val t = top.toInt().coerceIn(0, height - 1)
            val r = right.toInt().coerceIn(0, width - 1)
            val b = bottom.toInt().coerceIn(0, height - 1)
            for (y in t..b) {
                for (x in l..r) {
                    bm.setPixel(x, y, colorInt)
                }
            }
        }

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            val minX = (cx - r).toInt().coerceIn(0, width - 1)
            val maxX = (cx + r).toInt().coerceIn(0, width - 1)
            val minY = (cy - r).toInt().coerceIn(0, height - 1)
            val maxY = (cy + r).toInt().coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        val boardLeft = width * 0.055f
        val boardWidth = width * 0.89f
        val boardRight = boardLeft + boardWidth
        val boardTop = (height - boardWidth) * 0.48f
        val boardBottom = boardTop + boardWidth
        val bottomBaselineY = boardTop + boardWidth * 0.845f
        val topBaselineY = boardTop + boardWidth * 0.155f

        paintRect(boardLeft, boardTop, boardRight, boardBottom, android.graphics.Color.rgb(212, 170, 128))
        // Stationary small orange end-circles on bottom baseline (NO player striker)
        paintDisc(boardLeft + boardWidth * 0.175f, bottomBaselineY, 11f, android.graphics.Color.rgb(214, 122, 26))
        paintDisc(boardLeft + boardWidth * 0.825f, bottomBaselineY, 11f, android.graphics.Color.rgb(214, 122, 26))

        // Opponent striker sitting on TOP baseline + a couple of coins in center
        paintDisc(boardLeft + boardWidth * 0.50f, topBaselineY, 20f, android.graphics.Color.rgb(74, 42, 26))
        paintDisc(boardLeft + boardWidth * 0.36f, boardTop + boardWidth * 0.42f, 14f, android.graphics.Color.rgb(235, 228, 212))
        paintDisc(boardLeft + boardWidth * 0.62f, boardTop + boardWidth * 0.48f, 14f, android.graphics.Color.rgb(24, 24, 26))

        // Empty uniform brown slider track with NO knob
        val sliderY = boardBottom + height * 0.145f
        paintRect(
            boardLeft + boardWidth * 0.16f,
            sliderY - 11f,
            boardLeft + boardWidth * 0.84f,
            sliderY + 11f,
            android.graphics.Color.rgb(142, 92, 54)
        )

        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = bm,
            screenWidth = 1080,
            screenHeight = 2400,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )

        org.junit.Assert.assertTrue(
            "Expected WaitStillnessOrTurn during opponent top-baseline turn, got $outcome",
            outcome is com.example.game.CarromVisionOutcome.WaitStillnessOrTurn
        )

        bm.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `carrom vision detector solves real uploaded carrom disc pool screenshot immediately`() {
        val candidates = listOf(
            java.io.File("public/assets/aistudio/Screenshot_20261119_013520_Carrom_Disc_Pool_66343f37.jpg"),
            java.io.File("../public/assets/aistudio/Screenshot_20261119_013520_Carrom_Disc_Pool_66343f37.jpg")
        )
        val file = candidates.firstOrNull { it.exists() }
        org.junit.Assume.assumeNotNull(
            "Skipping optional local screenshot fixture on CI: not found from ${java.io.File(".").absolutePath}",
            file
        )

        val decoded = android.graphics.BitmapFactory.decodeFile(file!!.absolutePath)
        org.junit.Assert.assertNotNull(decoded)
        val raw = decoded!!
        val scaled = android.graphics.Bitmap.createScaledBitmap(raw, 540, 1200, true)

        com.example.game.CarromVisionDetector.resetMotionHistory()
        val outcome = com.example.game.CarromVisionDetector.analyzeAndSolve(
            screenshot = scaled,
            screenWidth = 1080,
            screenHeight = 2400,
            myCoinColor = com.example.game.CoinColor.WHITE,
            consecutiveOfflineWaits = 0
        )

        org.junit.Assert.assertTrue(
            "Expected SolvedShot on real Carrom Disc Pool screenshot, got $outcome",
            outcome is com.example.game.CarromVisionOutcome.SolvedShot
        )
        val solved = outcome as com.example.game.CarromVisionOutcome.SolvedShot
        org.junit.Assert.assertNotNull("Expected bottom striker detected", solved.detectedStriker)
        org.junit.Assert.assertNotNull("Expected bottom slider knob detected", solved.detectedSliderKnob)
        assertEquals(540f, solved.detectedStriker!!.center.x, 45f)

        scaled.recycle()
        if (raw !== scaled) raw.recycle()
        com.example.game.CarromVisionDetector.resetMotionHistory()
    }

    @Test
    fun `carrom circle refiner fixes glowing halo offset to sub-pixel precision and separates touching coins`() {
        val width = 300
        val height = 300
        val bm = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.rgb(205, 165, 110))

        fun paintDisc(cx: Float, cy: Float, r: Float, colorInt: Int) {
            val rSq = r * r
            for (y in (cy - r - 1).toInt().coerceIn(0, height - 1)..(cy + r + 1).toInt().coerceIn(0, height - 1)) {
                for (x in (cx - r - 1).toInt().coerceIn(0, width - 1)..(cx + r + 1).toInt().coerceIn(0, width - 1)) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= rSq) {
                        bm.setPixel(x, y, colorInt)
                    }
                }
            }
        }

        // True white disc at (80, 90) with radius 15px, coarse guess shifted +4px right, -3px up
        paintDisc(80f, 90f, 15f, android.graphics.Color.rgb(245, 245, 240))
        val refined = com.example.game.CarromCircleRefiner.refinePieceCenterSubPixel(
            bitmap = bm,
            coarseCenterScreen = com.example.game.Point2D(84f * 2f, 87f * 2f),
            expectedRadiusScreen = 15f * 2f,
            scaleX = 2f,
            scaleY = 2f
        )
        assertEquals(80f * 2f, refined.x, 2.5f)
        assertEquals(90f * 2f, refined.y, 2.5f)

        // Two touching white coins horizontally adjacent at (170, 180) and (200, 180), r=15
        paintDisc(170f, 180f, 15f, android.graphics.Color.rgb(246, 244, 238))
        paintDisc(200f, 180f, 15f, android.graphics.Color.rgb(246, 244, 238))
        val clusterPts = mutableListOf<com.example.game.Point2D>()
        for (y in 164..196 step 3) {
            for (x in 154..216 step 3) {
                val d1 = kotlin.math.hypot((x - 170).toFloat(), (y - 180).toFloat())
                val d2 = kotlin.math.hypot((x - 200).toFloat(), (y - 180).toFloat())
                if (d1 <= 15f || d2 <= 15f) {
                    clusterPts.add(com.example.game.Point2D(x * 2f, y * 2f))
                }
            }
        }
        val separated = com.example.game.CarromCircleRefiner.separateClusterByHough(
            bitmap = bm,
            clusterPointsScreen = clusterPts,
            colorHint = com.example.game.CoinColor.WHITE,
            coinRadiusScreen = 15f * 2f,
            scaleX = 2f,
            scaleY = 2f
        )
        org.junit.Assert.assertTrue(
            "Expected Hough cluster separation to find 2 touching coins, got ${separated.size}",
            separated.size == 2
        )
        bm.recycle()
    }

    @Test
    fun `physics simulator counts collision-assisted pot as Sure Shot while rejecting collision-miss and ranking up to 6 baseline probes`() {
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

        // Setup 1: Target white coin in front of Top-Left pocket, with a helper white coin right behind it
        // Hitting the helper coin drives the target white coin into Top-Left pocket (collision-assisted pot!)
        val targetCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(205f, 545f),
            radiusPx = 28f
        )
        val helperCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(248f, 588f),
            radiusPx = 28f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(869f, 1210f),
            radiusPx = 35f
        )
        val extraCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(420f, 700f),
            radiusPx = 28f
        )
        val pieces = listOf(targetCoin, helperCoin, extraCoin, striker)

        val dirToPocket = (board.pockets[0] - targetCoin.center).normalized()
        val ghostBehindHelper = helperCoin.center - dirToPocket * (28f + 35f)
        val dirToGhost = (ghostBehindHelper - striker.center).normalized()
        val collisionCandidate = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = dirToGhost,
            ghostBall = ghostBehindHelper,
            targetCoin = targetCoin,
            targetPocket = board.pockets[0],
            pocketIndex = 0,
            cutAngleDeg = 0f,
            isBankShot = false,
            isComboShot = true,
            isBreakShot = false,
            basePullMultiplier = 1.26f,
            staticScore = 0.72f,
            totalDistance = 920f
        )

        val simRes = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = pieces,
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(collisionCandidate),
            shotMemory = null,
            matchCalibration = null
        )
        org.junit.Assert.assertNotNull(simRes)
        org.junit.Assert.assertTrue(
            "Expected collision-assisted clean pot to qualify as Sure Shot, got ${simRes!!.second}",
            simRes.second.isSureShot && simRes.second.isCollisionAssistedSureShot
        )

        // Setup 2: Blocked shot with an opponent black coin deflecting the white coin away from pocket -> rejected
        val blockerBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(175f, 490f),
            radiusPx = 28f
        )
        val missSimRes = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = listOf(targetCoin, blockerBlack, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(
                com.example.game.RolloutCandidate(
                    strikerPos = striker.center,
                    baseDir = (targetCoin.center - striker.center).normalized(),
                    ghostBall = targetCoin.center,
                    targetCoin = targetCoin,
                    targetPocket = board.pockets[0],
                    pocketIndex = 0,
                    cutAngleDeg = 12f,
                    isBankShot = false,
                    isComboShot = false,
                    isBreakShot = false,
                    basePullMultiplier = 1.0f,
                    staticScore = 0.70f,
                    totalDistance = 740f
                )
            ),
            shotMemory = null,
            matchCalibration = null
        )
        org.junit.Assert.assertNotNull(missSimRes)
        org.junit.Assert.assertFalse(
            "Expected blocked collision miss NOT to be marked as Sure Shot",
            missSimRes!!.second.isSureShot
        )

        // Setup 3: Ranked top-6 probe queue + 7s cutoff enforcement in CarromTurnMemory
        val probeQueue = com.example.game.CarromGeometrySolver.buildRankedBaselineProbeQueue(
            board = board,
            pieces = pieces,
            myCoinColor = com.example.game.CoinColor.WHITE,
            existingStriker = striker,
            shotMemory = null,
            maxProbes = 6
        )
        org.junit.Assert.assertTrue("Expected ranked baseline probes <= 6 and non-empty", probeQueue.size in 1..6)

        val turnMemory = com.example.game.CarromTurnMemory()
        turnMemory.ensureTurnStarted(1_000L)
        turnMemory.initializeProbeQueueIfNeeded(probeQueue)
        turnMemory.markSpotRejected(striker.center.x, "collision-miss")
        org.junit.Assert.assertTrue(turnMemory.canAffordAnotherSliderMove(4_000L, isLiveStreamActive = true))
        org.junit.Assert.assertFalse(turnMemory.canAffordAnotherSliderMove(7_200L, isLiveStreamActive = true))
    }

    @Test
    fun `verified cache trail caches correct steps including swipes and purges wrong pages and wrong apps`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheStore = com.example.agent.ActionCacheStore(context)
        cacheStore.clearAll()

        fun node(
            id: Int,
            label: String,
            viewId: String,
            editable: Boolean = false
        ) = com.example.agent.ScreenNode(
            id = id,
            className = if (editable) "android.widget.EditText" else "android.widget.Button",
            text = label,
            contentDesc = "",
            viewId = viewId,
            bounds = com.example.agent.ScreenBounds(100, 200 + id * 120, 400, 300 + id * 120),
            isClickable = true,
            isEditable = editable,
            isScrollable = true,
            isChecked = false,
            isFocused = editable
        )

        val goal = "YouTube এ্যাপে গিয়ে Shorts স্লাইড করো"
        val drawerNodes = listOf(node(1, "Search", "btn_search"), node(2, "YouTube", "btn_yt"))
        val drawerSig = com.example.agent.ActionCacheStore.computeScreenSignature(drawerNodes)
        val drawerComposite = "com.sec.android.app.launcher::$drawerSig"

        val finderNodes = listOf(node(3, "Search", "edit_query", editable = true), node(4, "Voice", "btn_voice"))
        val finderSig = com.example.agent.ActionCacheStore.computeScreenSignature(finderNodes)
        val finderComposite = "com.samsung.android.app.galaxyfinder::$finderSig"

        val ytHomeNodes = listOf(node(5, "Shorts", "tab_shorts"), node(6, "Subscriptions", "tab_subs"))
        val ytHomeSig = com.example.agent.ActionCacheStore.computeScreenSignature(ytHomeNodes)
        val ytHomeComposite = "com.google.android.youtube::$ytHomeSig"

        val ytWrongSubsNodes = listOf(node(7, "All Subscriptions", "subs_header"), node(8, "Channel A", "ch_a"))
        val ytWrongSubsSig = com.example.agent.ActionCacheStore.computeScreenSignature(ytWrongSubsNodes)
        val ytWrongSubsComposite = "com.google.android.youtube::$ytWrongSubsSig"

        val ytShortsNodes = listOf(node(9, "Like", "like_btn"), node(10, "Comment", "comment_btn"))
        val ytShortsSig = com.example.agent.ActionCacheStore.computeScreenSignature(ytShortsNodes)
        val ytShortsComposite = "com.google.android.youtube::$ytShortsSig"

        val ytShortsNextNodes = listOf(node(11, "Share", "share_btn"), node(12, "Remix", "remix_btn"))
        val ytShortsNextSig = com.example.agent.ActionCacheStore.computeScreenSignature(ytShortsNextNodes)
        val ytShortsNextComposite = "com.google.android.youtube::$ytShortsNextSig"

        val trail = com.example.agent.VerifiedCacheTrail(cacheStore)

        // 1. Valid step: Drawer -> Finder (tap Search)
        val candTapSearch = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.sec.android.app.launcher",
            screenSignature = drawerSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.TAP,
                targetNodeId = 1,
                x = 250,
                y = 370,
                textToType = null,
                waitSeconds = null,
                memoryNote = null,
                reason = "Tap Search",
                requiresConfirmation = false
            ),
            targetNode = drawerNodes[0],
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candTapSearch,
            prevReplayedKey = null,
            preCompositeScreen = drawerComposite,
            currentCompositeScreen = finderComposite,
            currentPackage = "com.samsung.android.app.galaxyfinder",
            currentSignature = finderSig,
            prevActionType = AgentActionType.TAP
        )

        // 2. Wrong app detour: Finder -> WhatsApp instead of YouTube -> immediately purged on wasWrongPageOrApp!
        val candWrongApp = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.samsung.android.app.galaxyfinder",
            screenSignature = finderSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.TAP,
                targetNodeId = 4,
                x = 250,
                y = 730,
                textToType = null,
                waitSeconds = null,
                memoryNote = null,
                reason = "Tapped wrong app",
                requiresConfirmation = false
            ),
            targetNode = finderNodes[1],
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candWrongApp,
            prevReplayedKey = null,
            preCompositeScreen = finderComposite,
            currentCompositeScreen = "com.whatsapp::wrong_app",
            currentPackage = "com.whatsapp",
            currentSignature = "wrong_app",
            prevActionType = AgentActionType.TAP,
            wasWrongPageOrApp = true
        )
        // Pressed BACK to return to Finder -> valid candTapSearch stays staged!
        trail.onStepObserved(
            prevCandidate = null,
            prevReplayedKey = null,
            preCompositeScreen = "com.whatsapp::wrong_app",
            currentCompositeScreen = finderComposite,
            currentPackage = "com.samsung.android.app.galaxyfinder",
            currentSignature = finderSig,
            prevActionType = AgentActionType.BACK
        )

        // 3. Valid step: Finder -> YouTube Home (type YouTube)
        val candTypeYt = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.samsung.android.app.galaxyfinder",
            screenSignature = finderSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.INPUT_TEXT,
                targetNodeId = 3,
                x = 250,
                y = 610,
                textToType = "YouTube",
                waitSeconds = null,
                memoryNote = null,
                reason = "Search YouTube",
                requiresConfirmation = false
            ),
            targetNode = finderNodes[0],
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candTypeYt,
            prevReplayedKey = null,
            preCompositeScreen = finderComposite,
            currentCompositeScreen = ytHomeComposite,
            currentPackage = "com.google.android.youtube",
            currentSignature = ytHomeSig,
            prevActionType = AgentActionType.INPUT_TEXT
        )

        // 4. Inside YouTube Home: accidentally tapped Subscriptions (WRONG PAGE) -> then pressed BACK
        val candWrongTab = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.google.android.youtube",
            screenSignature = ytHomeSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.TAP,
                targetNodeId = 6,
                x = 250,
                y = 970,
                textToType = null,
                waitSeconds = null,
                memoryNote = null,
                reason = "Tap Subscriptions by mistake",
                requiresConfirmation = false
            ),
            targetNode = ytHomeNodes[1],
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candWrongTab,
            prevReplayedKey = null,
            preCompositeScreen = ytHomeComposite,
            currentCompositeScreen = ytWrongSubsComposite,
            currentPackage = "com.google.android.youtube",
            currentSignature = ytWrongSubsSig,
            prevActionType = AgentActionType.TAP
        )
        // Realized wrong page -> pressed BACK to YouTube Home
        trail.onStepObserved(
            prevCandidate = null,
            prevReplayedKey = null,
            preCompositeScreen = ytWrongSubsComposite,
            currentCompositeScreen = ytHomeComposite,
            currentPackage = "com.google.android.youtube",
            currentSignature = ytHomeSig,
            prevActionType = AgentActionType.BACK
        )

        // 5. Correct tap on Shorts -> arrived on Shorts
        val candTapShorts = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.google.android.youtube",
            screenSignature = ytHomeSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.TAP,
                targetNodeId = 5,
                x = 250,
                y = 850,
                textToType = null,
                waitSeconds = null,
                memoryNote = null,
                reason = "Tap Shorts tab",
                requiresConfirmation = false
            ),
            targetNode = ytHomeNodes[0],
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candTapShorts,
            prevReplayedKey = null,
            preCompositeScreen = ytHomeComposite,
            currentCompositeScreen = ytShortsComposite,
            currentPackage = "com.google.android.youtube",
            currentSignature = ytShortsSig,
            prevActionType = AgentActionType.TAP
        )

        // 6. Correct SWIPE_LEFT on Shorts -> advanced to ShortsNext, then COMPLETE
        val candSwipeLeft = com.example.agent.PendingCacheCandidate(
            goal = goal,
            packageName = "com.google.android.youtube",
            screenSignature = ytShortsSig,
            interactiveNodeCount = 2,
            action = com.example.agent.PlannedAction(
                actionType = AgentActionType.SWIPE_LEFT,
                targetNodeId = null,
                x = null,
                y = null,
                textToType = null,
                waitSeconds = null,
                memoryNote = null,
                reason = "Slide left on Shorts",
                requiresConfirmation = false
            ),
            targetNode = null,
            hadConfirmedSafety = false
        )
        trail.onStepObserved(
            prevCandidate = candSwipeLeft,
            prevReplayedKey = null,
            preCompositeScreen = ytShortsComposite,
            currentCompositeScreen = ytShortsNextComposite,
            currentPackage = "com.google.android.youtube",
            currentSignature = ytShortsNextSig,
            prevActionType = AgentActionType.SWIPE_LEFT
        )

        trail.commitAllOnComplete()

        val saved = cacheStore.cachedActions.value
        val savedLabels = saved.map { it.targetLabel }

        // Wrong app tap ("Voice") and wrong tab tap ("Subscriptions") must NOT be in cache!
        assertFalse("Wrong app tap should not be cached", "Voice" in savedLabels)
        assertFalse("Wrong page tap should not be cached", "Subscriptions" in savedLabels)

        // Verified correct steps ("Search", "Search" INPUT_TEXT, "Shorts", "Slide Left") MUST be cached & replayable!
        org.junit.Assert.assertTrue("Expected Shorts tab in cache, got $savedLabels", "Shorts" in savedLabels)
        org.junit.Assert.assertTrue("Expected Slide Left in cache, got $savedLabels", "Slide Left" in savedLabels)

        val replayOnYtHome = cacheStore.findReplayMatch(
            goal = goal,
            snapshot = com.example.agent.UiSnapshot("com.google.android.youtube", 1080, 2400, ytHomeNodes, null, "")
        )
        assertEquals(AgentActionType.TAP, replayOnYtHome?.action?.actionType)
        assertEquals(5, replayOnYtHome?.action?.targetNodeId)

        val replayOnShorts = cacheStore.findReplayMatch(
            goal = goal,
            snapshot = com.example.agent.UiSnapshot("com.google.android.youtube", 1080, 2400, ytShortsNodes, null, "")
        )
        assertEquals(AgentActionType.SWIPE_LEFT, replayOnShorts?.action?.actionType)

        cacheStore.clearAll()
    }

    @Test
    fun `gemini planner parses SWIPE_LEFT and SWIPE_RIGHT json cleanly`() {
        val planner = GeminiAiPlanner()
        val leftJson = """{"actionType":"SWIPE_LEFT","targetNodeId":null,"x":null,"y":1100,"reason":"Slide left to next tab","requiresConfirmation":false}"""
        val rightJson = """{"actionType":"SWIPE_RIGHT","targetNodeId":null,"x":null,"y":1100,"reason":"Slide right to previous card","requiresConfirmation":false}"""

        val parsedLeft = planner.parsePlannedAction(leftJson)
        val parsedRight = planner.parsePlannedAction(rightJson)
        assertEquals(AgentActionType.SWIPE_LEFT, parsedLeft.actionType)
        assertEquals(1100, parsedLeft.y)
        assertEquals(AgentActionType.SWIPE_RIGHT, parsedRight.actionType)
    }
}

