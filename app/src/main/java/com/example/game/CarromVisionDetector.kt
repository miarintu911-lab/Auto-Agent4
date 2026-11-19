package com.example.game

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

sealed interface CarromVisionOutcome {
    data class WaitStillnessOrTurn(
        val reason: String,
        val boardPieces: List<CarromPiece> = emptyList()
    ) : CarromVisionOutcome
    data class SolvedShot(
        val solution: CarromShotSolution,
        val detectedStriker: CarromPiece?,
        val board: CarromBoardLayout,
        val detectedSliderKnob: Point2D? = null,
        val boardPieces: List<CarromPiece> = emptyList()
    ) : CarromVisionOutcome
    data class NeedsAiVision(
        val diagnostic: String,
        val boardPieces: List<CarromPiece> = emptyList()
    ) : CarromVisionOutcome
}

data class CarromWatchObservation(
    val board: CarromBoardLayout,
    val pieces: List<CarromPiece>,
    val striker: CarromPiece?,
    val sliderKnob: Point2D?,
    val lumaDiff: Float,
    val isInMotion: Boolean,
    val isLikelyGameBoard: Boolean
)

/**
 * Pure Kotlin on-device Carrom screenshot analyzer + offline board-motion detector.
 * Zero native OpenCV bloat (~15-35ms per frame). Falls back cleanly to AI Vision
 * whenever board confidence is low or screen is a lobby/menu.
 */
object CarromVisionDetector {

    private const val GRID_SIZE = 16
    private const val SAMPLE_STEP = 4
    private const val MOTION_THRESHOLD = 13.5f

    private var previousBoardLuma: IntArray? = null

    fun resetMotionHistory() {
        previousBoardLuma = null
    }

    fun analyzeAndSolve(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        myCoinColor: CoinColor,
        consecutiveOfflineWaits: Int,
        shotMemory: CarromMemoryData? = null,
        allowStrikerRepositioning: Boolean = true,
        usePhysicsRollout: Boolean = true,
        useSubPixelRefinement: Boolean = true,
        isFreestyle: Boolean = false
    ): CarromVisionOutcome {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) {
            return CarromVisionOutcome.NeedsAiVision("No screenshot available for Carrom solver.")
        }

        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()

        // Estimate square Carrom board region (supports portrait & landscape + vertical HUD variance)
        val (boardBmLeft, boardBmTop, boardBmRight, boardBmBottom) = estimateBoardBitmapBounds(screenshot)
        val boardBmWidth = (boardBmRight - boardBmLeft).coerceAtLeast(40)
        val boardBmHeight = (boardBmBottom - boardBmTop).coerceAtLeast(40)

        // 1. Offline Motion / Stillness check on central playfield [0.16..0.76] (ignores pulsing baseline striker halo)
        val innerPadX = (boardBmWidth * 0.12f).toInt()
        val motionTop = (boardBmTop + (boardBmHeight * 0.16f).toInt()).coerceIn(0, screenshot.height - 2)
        val motionBottom = (boardBmTop + (boardBmHeight * 0.76f).toInt()).coerceIn(motionTop + 1, screenshot.height - 1)
        val currentLuma = sampleBoardLumaGrid(
            bitmap = screenshot,
            left = (boardBmLeft + innerPadX).coerceIn(0, screenshot.width - 2),
            top = motionTop,
            right = (boardBmRight - innerPadX).coerceIn(1, screenshot.width - 1),
            bottom = motionBottom
        )
        val prevLuma = previousBoardLuma
        previousBoardLuma = currentLuma

        val effectiveMotionThreshold = if (allowStrikerRepositioning) MOTION_THRESHOLD else MOTION_THRESHOLD * 1.55f
        if (prevLuma != null && consecutiveOfflineWaits < 2) {
            val meanDiff = computeMeanAbsDiff(prevLuma, currentLuma)
            if (meanDiff > effectiveMotionThreshold) {
                return CarromVisionOutcome.WaitStillnessOrTurn(
                    "Board motion detected (diff=${meanDiff.toInt()}) — waiting offline for coins to settle (0 API)."
                )
            }
        }

        // 2. Detect bottom slider knob (supports both vivid knobs and custom patterned skins via track-contrast)
        val sliderKnobPx = detectBottomSliderKnob(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY
        )

        val boardLeft = boardBmLeft * scaleX
        val boardTop = boardBmTop * scaleY
        val boardRight = boardBmRight * scaleX
        val boardBottom = boardBmBottom * scaleY
        val boardWidthPx = (boardRight - boardLeft).coerceAtLeast(100f)
        val boardHeightPx = (boardBottom - boardTop).coerceAtLeast(100f)

        val coinRadius = boardWidthPx * 0.029f
        val strikerRadius = boardWidthPx * 0.036f
        val pocketRadius = boardWidthPx * 0.052f
        val pocketInset = boardWidthPx * 0.064f

        // 3. Scan board region into clustered pieces (using Hough Circle separation for touching break clusters)
        val rawPieces = detectBoardPieces(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = coinRadius,
            useSubPixelRefinement = useSubPixelRefinement
        )

        val pockets = listOf(
            Point2D(boardLeft + pocketInset, boardTop + pocketInset),
            Point2D(boardRight - pocketInset, boardTop + pocketInset),
            Point2D(boardLeft + pocketInset, boardBottom - pocketInset),
            Point2D(boardRight - pocketInset, boardBottom - pocketInset)
        )
        val defaultBaselineY = boardTop + boardHeightPx * 0.845f

        // 4. Color-agnostic bottom baseline striker detection (matches ANY striker color/skin/halo)
        val contrastStriker = if (rawPieces.none { it.color == CoinColor.STRIKER }) {
            detectAnyStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                sliderKnobPx = sliderKnobPx
            )
        } else {
            null
        }

        val prelimStrikerRaw = contrastStriker ?: rawPieces.firstOrNull { it.color == CoinColor.STRIKER }
        val prelimStriker = if (prelimStrikerRaw != null && useSubPixelRefinement) {
            val refinedCenter = CarromCircleRefiner.refinePieceCenterSubPixel(
                bitmap = screenshot,
                coarseCenterScreen = prelimStrikerRaw.center,
                expectedRadiusScreen = strikerRadius,
                scaleX = scaleX,
                scaleY = scaleY
            )
            prelimStrikerRaw.copy(center = refinedCenter)
        } else {
            prelimStrikerRaw
        }
        val cleanedPieces = if (prelimStriker != null) {
            val clearR = strikerRadius * 1.40f
            rawPieces.filter { p ->
                p.color != CoinColor.STRIKER && (p.center - prelimStriker.center).length() > clearR
            } + prelimStriker
        } else {
            rawPieces
        }

        val promoted = promoteOrAnchorStriker(
            pieces = cleanedPieces,
            baselineY = defaultBaselineY,
            boardLeft = boardLeft,
            boardRight = boardRight,
            boardHeightPx = boardHeightPx,
            strikerRadiusPx = strikerRadius,
            sliderKnobPx = sliderKnobPx
        )

        if (promoted.size < 2) {
            return CarromVisionOutcome.NeedsAiVision(
                diagnostic = "Detected ${promoted.size} Carrom piece(s) (slider=${sliderKnobPx != null}); delegating to AI Vision.",
                boardPieces = promoted
            )
        }

        val strikerPiece = promoted.firstOrNull { it.color == CoinColor.STRIKER }
        if (strikerPiece == null && sliderKnobPx == null) {
            return if (consecutiveOfflineWaits < 1) {
                // Wait at most 1 short cycle if baseline looks empty (opponent turn), then let AI inspect.
                CarromVisionOutcome.WaitStillnessOrTurn(
                    reason = "Striker/slider not on bottom baseline (likely opponent turn) — waiting once offline (0 API).",
                    boardPieces = promoted
                )
            } else {
                CarromVisionOutcome.NeedsAiVision(
                    diagnostic = "Striker not matched locally (${promoted.size} coins visible, no slider knob); delegating shot to AI Vision.",
                    boardPieces = promoted
                )
            }
        }

        val calibratedBaselineY = strikerPiece?.center?.y ?: defaultBaselineY
        val boardLayout = CarromBoardLayout(
            left = boardLeft,
            top = boardTop,
            right = boardRight,
            bottom = boardBottom,
            pockets = pockets,
            baselineY = calibratedBaselineY,
            coinRadiusPx = coinRadius,
            strikerRadiusPx = strikerRadius,
            pocketRadiusPx = pocketRadius
        )

        val solution = CarromGeometrySolver.solveBestShot(
            board = boardLayout,
            pieces = promoted,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = strikerPiece,
            canRepositionStriker = allowStrikerRepositioning && (sliderKnobPx != null),
            shotMemory = shotMemory,
            usePhysicsRollout = usePhysicsRollout,
            isFreestyle = isFreestyle
        )

        return if (solution != null && solution.confidenceScore >= 0.50f) {
            CarromVisionOutcome.SolvedShot(
                solution = solution,
                detectedStriker = strikerPiece,
                board = boardLayout,
                detectedSliderKnob = sliderKnobPx,
                boardPieces = promoted
            )
        } else {
            val myTargetsCount = if (isFreestyle) {
                promoted.count { it.color != CoinColor.STRIKER }
            } else {
                promoted.count { it.color == myCoinColor || it.color == CoinColor.QUEEN }
            }
            CarromVisionOutcome.NeedsAiVision(
                diagnostic = "No offline shot (targets=$myTargetsCount, total=${promoted.size}, striker=${strikerPiece != null}); using AI Vision.",
                boardPieces = promoted
            )
        }
    }

    fun inspectBoardCounts(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        myCoinColor: CoinColor
    ): BoardPieceCounts? {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) return null
        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()
        val bounds = estimateBoardBitmapBounds(screenshot)
        val expectedCoinR = ((bounds.right - bounds.left) * scaleX * 0.029f).coerceAtLeast(16f)
        val raw = detectBoardPieces(
            bitmap = screenshot,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = expectedCoinR,
            useSubPixelRefinement = false
        )
        if (raw.isEmpty()) return null
        return CarromShotMemoryStore.countBoardPieces(raw, myCoinColor)
    }

    fun observeBoardForTraining(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int
    ): CarromWatchObservation? {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) return null

        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()
        val bounds = estimateBoardBitmapBounds(screenshot)
        val boardBmLeft = bounds.left
        val boardBmTop = bounds.top
        val boardBmRight = bounds.right
        val boardBmBottom = bounds.bottom
        val boardBmWidth = (boardBmRight - boardBmLeft).coerceAtLeast(40)
        val boardBmHeight = (boardBmBottom - boardBmTop).coerceAtLeast(40)

        val innerPadX = (boardBmWidth * 0.12f).toInt()
        val motionTop = (boardBmTop + (boardBmHeight * 0.16f).toInt()).coerceIn(0, screenshot.height - 2)
        val motionBottom = (boardBmTop + (boardBmHeight * 0.76f).toInt()).coerceIn(motionTop + 1, screenshot.height - 1)
        val currentLuma = sampleBoardLumaGrid(
            bitmap = screenshot,
            left = (boardBmLeft + innerPadX).coerceIn(0, screenshot.width - 2),
            top = motionTop,
            right = (boardBmRight - innerPadX).coerceIn(1, screenshot.width - 1),
            bottom = motionBottom
        )
        val prevLuma = previousBoardLuma
        previousBoardLuma = currentLuma
        val meanDiff = if (prevLuma != null) computeMeanAbsDiff(prevLuma, currentLuma) else 0f
        val isInMotion = prevLuma != null && meanDiff > (MOTION_THRESHOLD * 0.90f)

        val sliderKnobPx = detectBottomSliderKnob(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY
        )

        val boardLeft = boardBmLeft * scaleX
        val boardTop = boardBmTop * scaleY
        val boardRight = boardBmRight * scaleX
        val boardBottom = boardBmBottom * scaleY
        val boardWidthPx = (boardRight - boardLeft).coerceAtLeast(100f)
        val boardHeightPx = (boardBottom - boardTop).coerceAtLeast(100f)
        val coinRadius = boardWidthPx * 0.029f
        val strikerRadius = boardWidthPx * 0.036f
        val pocketRadius = boardWidthPx * 0.052f
        val pocketInset = boardWidthPx * 0.064f

        val rawPieces = detectBoardPieces(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = coinRadius,
            useSubPixelRefinement = !isInMotion
        )

        val defaultBaselineY = boardTop + boardHeightPx * 0.845f
        val contrastStriker = if (rawPieces.none { it.color == CoinColor.STRIKER }) {
            detectAnyStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                sliderKnobPx = sliderKnobPx
            )
        } else {
            null
        }
        val prelimStriker = contrastStriker ?: rawPieces.firstOrNull { it.color == CoinColor.STRIKER }
        val cleanedPieces = if (prelimStriker != null) {
            val clearR = strikerRadius * 1.40f
            rawPieces.filter { p ->
                p.color != CoinColor.STRIKER && (p.center - prelimStriker.center).length() > clearR
            } + prelimStriker
        } else {
            rawPieces
        }
        val promoted = promoteOrAnchorStriker(
            pieces = cleanedPieces,
            baselineY = defaultBaselineY,
            boardLeft = boardLeft,
            boardRight = boardRight,
            boardHeightPx = boardHeightPx,
            strikerRadiusPx = strikerRadius,
            sliderKnobPx = sliderKnobPx
        )
        val strikerPiece = promoted.firstOrNull { it.color == CoinColor.STRIKER }
        val calibratedBaselineY = strikerPiece?.center?.y ?: defaultBaselineY
        val pockets = listOf(
            Point2D(boardLeft + pocketInset, boardTop + pocketInset),
            Point2D(boardRight - pocketInset, boardTop + pocketInset),
            Point2D(boardLeft + pocketInset, boardBottom - pocketInset),
            Point2D(boardRight - pocketInset, boardBottom - pocketInset)
        )
        val boardLayout = CarromBoardLayout(
            left = boardLeft,
            top = boardTop,
            right = boardRight,
            bottom = boardBottom,
            pockets = pockets,
            baselineY = calibratedBaselineY,
            coinRadiusPx = coinRadius,
            strikerRadiusPx = strikerRadius,
            pocketRadiusPx = pocketRadius
        )
        val nonStrikerCount = promoted.count { it.color != CoinColor.STRIKER }
        return CarromWatchObservation(
            board = boardLayout,
            pieces = promoted,
            striker = strikerPiece,
            sliderKnob = sliderKnobPx,
            lumaDiff = meanDiff,
            isInMotion = isInMotion,
            isLikelyGameBoard = nonStrikerCount >= 2
        )
    }

    private data class BoardBmBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun estimateBoardBitmapBounds(bitmap: Bitmap): BoardBmBounds {
        val w = bitmap.width
        val h = bitmap.height
        val isLandscape = w > h

        if (isLandscape) {
            val side = (h * 0.84f).toInt().coerceAtLeast(40)
            val left = ((w - side) / 2).coerceIn(0, w - 40)
            val top = ((h - side) / 2).coerceIn(0, h - 40)
            return BoardBmBounds(
                left = left,
                top = top,
                right = (left + side).coerceAtMost(w - 1),
                bottom = (top + side).coerceAtMost(h - 1)
            )
        }

        val left = (w * 0.055f).toInt()
        val right = (w * 0.945f).toInt()
        val boardW = (right - left).coerceAtLeast(40)
        val defaultTop = ((h - boardW) * 0.48f).toInt().coerceIn(0, h - 40)

        // Refine top within +-12% of screen height using symmetric top + bottom board border contrast
        val xStart = left + (boardW * 0.25f).toInt()
        val xEnd = right - (boardW * 0.25f).toInt()
        val searchMinY = (defaultTop - (h * 0.12f).toInt()).coerceAtLeast(0)
        val searchMaxY = (defaultTop + (h * 0.12f).toInt()).coerceAtMost(h - boardW - 1).coerceAtLeast(searchMinY)

        fun avgRowLuma(py: Int): Float {
            val clampedY = py.coerceIn(0, h - 1)
            var rowLuma = 0
            var count = 0
            var px = xStart
            while (px <= xEnd) {
                val p = bitmap.getPixel(px, clampedY)
                rowLuma += (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                count++
                px += 12
            }
            return if (count > 0) rowLuma.toFloat() / count else 0f
        }

        val edgePadIn = (boardW * 0.08f).toInt().coerceAtLeast(10)
        val edgePadOut = (boardW * 0.045f).toInt().coerceAtLeast(10)

        fun scoreCandidate(candidateTop: Int): Float {
            val topContrast = avgRowLuma(candidateTop + edgePadIn) - avgRowLuma(candidateTop - edgePadOut)
            val bottomContrast = avgRowLuma(candidateTop + boardW - edgePadIn) - avgRowLuma(candidateTop + boardW + edgePadOut)
            return minOf(topContrast, bottomContrast) + (topContrast + bottomContrast) * 0.5f
        }

        val defaultScore = scoreCandidate(defaultTop)
        var bestTop = defaultTop
        var bestScore = defaultScore
        var y = searchMinY
        while (y <= searchMaxY) {
            val score = scoreCandidate(y)
            if (score > bestScore) {
                bestScore = score
                bestTop = y
            }
            y += 6
        }

        val finalTop = if (bestScore > defaultScore + 8f && bestScore > 16f) bestTop else defaultTop
        val finalBottom = (finalTop + boardW).coerceAtMost(h - 1)
        return BoardBmBounds(left = left, top = finalTop, right = right, bottom = finalBottom)
    }

    private fun detectBottomSliderKnob(
        bitmap: Bitmap,
        boardLeft: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float
    ): Point2D? {
        val sliderTop = (boardBottom + (bitmap.height * 0.012f).toInt()).coerceIn(0, bitmap.height - 2)
        val sliderBottom = (boardBottom + (bitmap.height * 0.21f).toInt()).coerceIn(sliderTop + 1, bitmap.height - 1)
        val bw = (boardRight - boardLeft).coerceAtLeast(1)
        val bh = (sliderBottom - sliderTop).coerceAtLeast(1)
        val cols = (bw / SAMPLE_STEP).coerceAtLeast(1)
        val rows = (bh / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 2) return null

        val active = Array(rows) { BooleanArray(cols) }
        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val py = (sliderTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (boardLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                rgbToHsv(bitmap.getPixel(px, py), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                // Slider knobs are vivid/bright discs (gold/amber, cyan/blue, emerald, ruby, silver/white) — exclude muted tan wood
                active[r][c] = (s >= 0.58f && v >= 0.65f && h in 20f..66f) ||
                    (s >= 0.42f && v >= 0.50f && h in 158f..235f) ||
                    (s >= 0.45f && v >= 0.48f && h in 88f..157f) ||
                    (s >= 0.55f && v >= 0.55f && (h <= 18f || h >= 336f)) ||
                    (s <= 0.24f && v >= 0.84f)
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        var bestCenter: Point2D? = null
        var bestCount = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!active[r][c] || visited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (boardLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (sliderTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && !visited[nr][nc] && active[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                // Knob should be a compact disc (not a wide full-width track/bar)
                if (count in 4..180 && count > bestCount) {
                    bestCount = count
                    bestCenter = Point2D(sumX / count, sumY / count)
                }
            }
        }
        if (bestCenter != null) return bestCenter

        // Fallback: row-wise track-contrast check catches patterned/brown/dark/custom striker skins inside the slider bar
        val trackColStart = (cols * 0.18f).toInt().coerceIn(0, cols - 1)
        val trackColEnd = (cols * 0.82f).toInt().coerceIn(trackColStart, cols - 1)
        val contrastActive = Array(rows) { BooleanArray(cols) }
        val rowR = IntArray(trackColEnd - trackColStart + 1)
        val rowG = IntArray(trackColEnd - trackColStart + 1)
        val rowB = IntArray(trackColEnd - trackColStart + 1)

        for (r in 0 until rows) {
            val py = (sliderTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            var idx = 0
            for (c in trackColStart..trackColEnd) {
                val px = (boardLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                rowR[idx] = Color.red(p)
                rowG[idx] = Color.green(p)
                rowB[idx] = Color.blue(p)
                idx++
            }
            val medR = medianOfCopy(rowR, idx)
            val medG = medianOfCopy(rowG, idx)
            val medB = medianOfCopy(rowB, idx)

            for (c in trackColStart..trackColEnd) {
                val px = (boardLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val diff = abs(Color.red(p) - medR) + abs(Color.green(p) - medG) + abs(Color.blue(p) - medB)
                contrastActive[r][c] = diff >= 54
            }
        }

        val cVisited = Array(rows) { BooleanArray(cols) }
        var contrastBestCenter: Point2D? = null
        var contrastBestCount = 0
        for (r in 0 until rows) {
            for (c in trackColStart..trackColEnd) {
                if (!contrastActive[r][c] || cVisited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                queue.add(r to c)
                cVisited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (boardLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (sliderTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    if (cr < minR) minR = cr
                    if (cr > maxR) maxR = cr
                    if (cc < minC) minC = cc
                    if (cc > maxC) maxC = cc
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in trackColStart..trackColEnd &&
                                !cVisited[nr][nc] && contrastActive[nr][nc]
                            ) {
                                cVisited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                val spanR = (maxR - minR + 1).coerceAtLeast(1)
                val spanC = (maxC - minC + 1).coerceAtLeast(1)
                val aspect = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).toFloat()
                if (count in 5..180 && spanR >= 2 && spanC <= (cols * 0.16f).toInt().coerceAtLeast(8) && aspect <= 2.4f && count > contrastBestCount) {
                    contrastBestCount = count
                    contrastBestCenter = Point2D(sumX / count, sumY / count)
                }
            }
        }
        return contrastBestCenter
    }

    private fun promoteOrAnchorStriker(
        pieces: List<CarromPiece>,
        baselineY: Float,
        boardLeft: Float,
        boardRight: Float,
        boardHeightPx: Float,
        strikerRadiusPx: Float,
        sliderKnobPx: Point2D?
    ): List<CarromPiece> {
        if (pieces.any { it.color == CoinColor.STRIKER }) return pieces

        val baselineTolerance = boardHeightPx * 0.085f
        val baselineCandidates = pieces.filter { abs(it.center.y - baselineY) <= baselineTolerance }

        // 1. If bottom slider knob is detected, match the piece on the baseline closest to slider X
        if (sliderKnobPx != null) {
            val maxHorizDelta = (boardRight - boardLeft) * 0.16f
            val matchedBySlider = baselineCandidates.minByOrNull { abs(it.center.x - sliderKnobPx.x) }
            if (matchedBySlider != null && abs(matchedBySlider.center.x - sliderKnobPx.x) <= maxHorizDelta) {
                return pieces.map { piece ->
                    if (piece === matchedBySlider) piece.copy(color = CoinColor.STRIKER) else piece
                }
            }
            // Even if the striker has an uncommon dark/patterned skin, its position matches sliderKnobPx.x on baselineY!
            val clampedX = sliderKnobPx.x.coerceIn(
                boardLeft + strikerRadiusPx * 2.1f,
                boardRight - strikerRadiusPx * 2.1f
            )
            return pieces + CarromPiece(
                color = CoinColor.STRIKER,
                center = Point2D(clampedX, baselineY),
                radiusPx = strikerRadiusPx
            )
        }

        // 2. Fallback without slider: promote only a genuine striker-sized disc inside the playable middle baseline
        val avgRadius = pieces.map { it.radiusPx }.average().toFloat().coerceAtLeast(14f)
        val midLeft = boardLeft + (boardRight - boardLeft) * 0.21f
        val midRight = boardLeft + (boardRight - boardLeft) * 0.79f
        val tightBaselineCandidate = baselineCandidates
            .filter {
                abs(it.center.y - baselineY) <= boardHeightPx * 0.055f &&
                    it.center.x in midLeft..midRight
            }
            .maxByOrNull { it.radiusPx }
        if (tightBaselineCandidate != null &&
            tightBaselineCandidate.radiusPx >= maxOf(avgRadius * 1.10f, strikerRadiusPx * 0.90f)
        ) {
            return pieces.map { piece ->
                if (piece === tightBaselineCandidate) piece.copy(color = CoinColor.STRIKER) else piece
            }
        }

        return pieces
    }

    private fun detectBoardPieces(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        scaleX: Float,
        scaleY: Float,
        expectedCoinRadiusPx: Float = 28f,
        useSubPixelRefinement: Boolean = true
    ): List<CarromPiece> {
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val cols = (bw / SAMPLE_STEP).coerceAtLeast(1)
        val rows = (bh / SAMPLE_STEP).coerceAtLeast(1)

        val labels = Array(rows) { arrayOfNulls<CoinColor>(cols) }
        val hsv = FloatArray(3)
        val pocketMargin = (bw * 0.11f).toInt()

        for (r in 0 until rows) {
            val py = (top + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (left + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)

                // Skip dark corner pocket holes
                val dx = px - left
                val dy = py - top
                val nearCorner = (dx < pocketMargin || dx > bw - pocketMargin) &&
                    (dy < pocketMargin || dy > bh - pocketMargin)
                if (nearCorner) continue

                val pixel = bitmap.getPixel(px, py)
                rgbToHsv(pixel, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                val isBaselineBand = abs(dy - bh * 0.845f) < bh * 0.105f

                labels[r][c] = when {
                    // Striker: distinct colored disc/ring on baseline (cyan/blue, vivid gold, purple, green)
                    isBaselineBand && (
                        (h in 162f..245f && s >= 0.34f && v >= 0.40f) ||
                            (h in 36f..62f && s >= 0.56f && v >= 0.70f) ||
                            (h in 260f..325f && s >= 0.40f && v >= 0.40f) ||
                            (h in 85f..155f && s >= 0.42f && v >= 0.42f)
                        ) -> CoinColor.STRIKER
                    // Queen: saturated red/crimson
                    (h >= 338f || h <= 16f) && s >= 0.48f && v >= 0.35f -> CoinColor.QUEEN
                    // White coin: bright ivory/white with low saturation (safely excludes tan wood s>=0.35)
                    s <= 0.28f && v >= 0.69f -> CoinColor.WHITE
                    // Black coin: dark grey/charcoal disc inside wooden board
                    v in 0.06f..0.30f && s <= 0.42f -> CoinColor.BLACK
                    else -> null
                }
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val detected = mutableListOf<CarromPiece>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        val bucketCellPx = (bw * scaleX * 0.056f).coerceAtLeast(24f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = labels[r][c] ?: continue
                if (visited[r][c]) continue

                var sumX = 0f
                var sumY = 0f
                var count = 0
                val clusterPoints = ArrayList<Point2D>(64)
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    val sx = (left + cc * SAMPLE_STEP) * scaleX
                    val sy = (top + cr * SAMPLE_STEP) * scaleY
                    sumX += sx
                    sumY += sy
                    count++
                    if (clusterPoints.size < 900) {
                        clusterPoints.add(Point2D(sx, sy))
                    }

                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols &&
                                !visited[nr][nc] && labels[nr][nc] == color
                            ) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                if (color == CoinColor.STRIKER) {
                    if (count in 4..260) {
                        val coarse = Point2D(sumX / count, sumY / count)
                        val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                            .coerceIn(14f, 68f)
                        val refined = if (useSubPixelRefinement) {
                            CarromCircleRefiner.refinePieceCenterSubPixel(bitmap, coarse, radiusApprox, scaleX, scaleY)
                        } else coarse
                        detected.add(CarromPiece(color, refined, radiusApprox))
                    }
                } else if (count in 4..75) {
                    val coarse = Point2D(sumX / count, sumY / count)
                    val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                        .coerceIn(14f, 68f)
                    val refined = if (useSubPixelRefinement) {
                        CarromCircleRefiner.refinePieceCenterSubPixel(bitmap, coarse, expectedCoinRadiusPx, scaleX, scaleY)
                    } else coarse
                    detected.add(CarromPiece(color, refined, radiusApprox))
                } else if (count in 76..900) {
                    // Use Hough Circle separation to extract exact individual disc centers inside touching break clusters
                    val houghSeparated = if (useSubPixelRefinement) {
                        CarromCircleRefiner.separateClusterByHough(
                            bitmap = bitmap,
                            clusterPointsScreen = clusterPoints,
                            colorHint = color,
                            coinRadiusScreen = expectedCoinRadiusPx,
                            scaleX = scaleX,
                            scaleY = scaleY
                        )
                    } else {
                        emptyList()
                    }
                    if (houghSeparated.isNotEmpty()) {
                        detected.addAll(houghSeparated)
                    } else {
                        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<Point2D>>()
                        for (pt in clusterPoints) {
                            val bx = (pt.x / bucketCellPx).toInt()
                            val by = (pt.y / bucketCellPx).toInt()
                            buckets.getOrPut(bx to by) { mutableListOf() }.add(pt)
                        }
                        for ((_, pts) in buckets) {
                            if (pts.size >= 3) {
                                val avgX = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
                                val avgY = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
                                detected.add(CarromPiece(color, Point2D(avgX, avgY), (bucketCellPx * 0.48f).coerceIn(16f, 44f)))
                            }
                        }
                    }
                }
            }
        }

        return detected
    }

    private fun detectAnyStrikerOnBaseline(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float,
        strikerRadiusPx: Float,
        sliderKnobPx: Point2D?
    ): CarromPiece? {
        val bw = (boardRight - boardLeft).coerceAtLeast(40)
        val bh = (boardBottom - boardTop).coerceAtLeast(40)
        val (woodR, woodG, woodB) = estimateBoardWoodRgb(bitmap, boardLeft, boardTop, boardRight, boardBottom)

        val scanLeft = (boardLeft + (bw * 0.14f).toInt()).coerceIn(0, bitmap.width - 2)
        val scanRight = (boardLeft + (bw * 0.86f).toInt()).coerceIn(scanLeft + 2, bitmap.width - 1)
        val scanTop = (boardTop + (bh * 0.76f).toInt()).coerceIn(0, bitmap.height - 2)
        val scanBottom = (boardTop + (bh * 0.93f).toInt()).coerceIn(scanTop + 2, bitmap.height - 1)

        val cols = ((scanRight - scanLeft) / SAMPLE_STEP).coerceAtLeast(1)
        val rows = ((scanBottom - scanTop) / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 3) return null

        val nonWood = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            val py = (scanTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (scanLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val diff = abs(Color.red(p) - woodR) + abs(Color.green(p) - woodG) + abs(Color.blue(p) - woodB)
                nonWood[r][c] = diff >= 56
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        val innerSafeLeftPx = (boardLeft + bw * 0.21f) * scaleX
        val innerSafeRightPx = (boardLeft + bw * 0.79f) * scaleX
        val boardWidthPx = bw * scaleX

        var bestPiece: CarromPiece? = null
        var bestScore = 0f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!nonWood[r][c] || visited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (scanLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (scanTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    if (cr < minR) minR = cr
                    if (cr > maxR) maxR = cr
                    if (cc < minC) minC = cc
                    if (cc > maxC) maxC = cc
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && !visited[nr][nc] && nonWood[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                val spanR = (maxR - minR + 1).coerceAtLeast(1)
                val spanC = (maxC - minC + 1).coerceAtLeast(1)
                val aspect = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).toFloat()
                if (count !in 8..280 || spanR < 3 || spanC < 3 || aspect > 2.2f) continue

                val cx = sumX / count
                val cy = sumY / count
                val inSafeMidZone = cx in innerSafeLeftPx..innerSafeRightPx
                val nearSlider = sliderKnobPx != null && abs(cx - sliderKnobPx.x) <= boardWidthPx * 0.14f
                val approxUnclampedR = kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX
                if (!inSafeMidZone && !nearSlider && approxUnclampedR < strikerRadiusPx * 0.92f) {
                    // Ignore small stationary orange baseline end-circles at any screen DPI when no slider knob is near
                    continue
                }

                val score = count.toFloat() + (if (nearSlider) 120f else 0f) + (if (inSafeMidZone) 40f else 0f)
                if (score > bestScore) {
                    bestScore = score
                    val approxR = approxUnclampedR.coerceIn(strikerRadiusPx * 0.8f, strikerRadiusPx * 1.45f)
                    bestPiece = CarromPiece(CoinColor.STRIKER, Point2D(cx, cy), approxR)
                }
            }
        }
        return bestPiece
    }

    private fun estimateBoardWoodRgb(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Triple<Int, Int, Int> {
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val maxSamples = 144
        val rs = IntArray(maxSamples)
        val gs = IntArray(maxSamples)
        val bs = IntArray(maxSamples)
        var count = 0

        for (gy in 0 until 12) {
            val fy = 0.18f + gy * (0.64f / 11f)
            val py = (top + bh * fy).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until 12) {
                val fx = 0.18f + gx * (0.64f / 11f)
                // Skip center break-rack region so clustered coins don't skew median wood color
                if (fx in 0.34f..0.66f && fy in 0.34f..0.66f) continue
                val px = (left + bw * fx).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                if (count < maxSamples) {
                    rs[count] = Color.red(p)
                    gs[count] = Color.green(p)
                    bs[count] = Color.blue(p)
                    count++
                }
            }
        }
        if (count == 0) return Triple(205, 165, 110)
        return Triple(medianOfCopy(rs, count), medianOfCopy(gs, count), medianOfCopy(bs, count))
    }

    private fun medianOfCopy(values: IntArray, length: Int): Int {
        if (length <= 0) return 0
        val copy = values.copyOf(length)
        copy.sort()
        return copy[length / 2]
    }

    private fun sampleBoardLumaGrid(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray {
        val out = IntArray(GRID_SIZE * GRID_SIZE)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        var idx = 0
        for (gy in 0 until GRID_SIZE) {
            val py = (top + (gy + 0.5f) * h / GRID_SIZE).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until GRID_SIZE) {
                val px = (left + (gx + 0.5f) * w / GRID_SIZE).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val luma = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                out[idx++] = luma
            }
        }
        return out
    }

    private fun computeMeanAbsDiff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var total = 0L
        for (i in a.indices) {
            total += abs(a[i] - b[i])
        }
        return total.toFloat() / a.size
    }

    private fun rgbToHsv(pixel: Int, out: FloatArray) {
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        var hue = when {
            delta < 1e-5f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val sat = if (maxC < 1e-5f) 0f else delta / maxC

        out[0] = hue
        out[1] = sat
        out[2] = maxC
    }
}
