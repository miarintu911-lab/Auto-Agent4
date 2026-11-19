package com.example.game

import kotlin.math.abs
import kotlin.math.hypot

enum class TurnProbeStage(val badge: String) {
    PRE_SCAN("PRE-SCAN"),
    PROBING_SLIDER("PROBING"),
    LOCKED_SURE_SHOT("SURE SHOT"),
    CAROM_SURE_SHOT("CAROM SURE"),
    FALLBACK_FIRE("6.4s BREAK/POT")
}

data class CarromTurnHudState(
    val isActive: Boolean = false,
    val elapsedSec: Float = 0f,
    val maxSec: Float = 7.0f,
    val probeNumber: Int = 0,
    val maxProbes: Int = 6,
    val scanCount: Int = 0,
    val rejectedCount: Int = 0,
    val stage: TurnProbeStage = TurnProbeStage.PRE_SCAN,
    val detail: String = ""
)

/**
 * Per-Turn Working Memory, Ranked 6-Spot Striker Probe Queue & 7-Second Turn Shot Clock.
 *
 * 1. Scans the whole board before moving and stabilizes piece coordinates across frames.
 * 2. Holds up to 6 physics-ranked baseline X candidate positions.
 * 3. Rejects colliding spots that miss or foul and slides to the next candidate spot (up to 6 moves).
 * 4. Accepts collision-assisted shots that STILL cleanly sink our coin/Queen without foul as Sure Shots.
 * 5. Enforces a 6.35s fallback deadline so the safest zero-foul break/pot fires before the 7.0s turn clock expires.
 */
class CarromTurnMemory {

    companion object {
        const val MAX_TURN_BUDGET_MS = 7_000L
        const val FALLBACK_FIRE_ELAPSED_MS = 6_350L
        const val MAX_BASELINE_PROBES = 6
        private const val LIVE_FIRE_RESERVE_MS = 950L
        private const val A11Y_FIRE_RESERVE_MS = 1_900L
        private const val MAX_STORED_SCANS = 20
        private const val SPOT_MATCH_TOLERANCE_PX = 20f
    }

    data class TurnScanFrame(
        val timestampMs: Long,
        val strikerX: Float?,
        val sliderKnobX: Float?,
        val pieces: List<CarromPiece>,
        val bestConfidence: Float,
        val isSureShot: Boolean
    )

    private var turnStartMs: Long = 0L
    private val scanFrames = mutableListOf<TurnScanFrame>()
    private val visitedStrikerXs = mutableListOf<Float>()
    private val localSliderGains = mutableListOf<Float>()
    private val rankedProbeQueue = mutableListOf<RankedBaselineSpot>()
    private val rejectedSpots = mutableListOf<Pair<Float, String>>()
    private var probeQueueInitialized = false
    private var _sliderMovesThisTurn: Int = 0
    private var bestEvaluatedThisTurn: CarromShotSolution? = null

    val sliderMovesThisTurn: Int get() = _sliderMovesThisTurn
    val scanCount: Int get() = scanFrames.size
    val isTurnActive: Boolean get() = turnStartMs > 0L
    val isProbeQueueInitialized: Boolean get() = probeQueueInitialized
    val rejectedProbeCount: Int get() = rejectedSpots.size
    val totalPlannedProbes: Int get() = rankedProbeQueue.size.coerceAtMost(MAX_BASELINE_PROBES)

    fun ensureTurnStarted(nowMs: Long = System.currentTimeMillis()) {
        if (turnStartMs == 0L) {
            turnStartMs = nowMs
        }
    }

    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (turnStartMs == 0L) return 0L
        return (nowMs - turnStartMs).coerceAtLeast(0L)
    }

    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (turnStartMs == 0L) return MAX_TURN_BUDGET_MS
        return (MAX_TURN_BUDGET_MS - elapsedMs(nowMs)).coerceAtLeast(0L)
    }

    /**
     * True when fewer than 6 slider moves have been dispatched AND there is enough room inside
     * the 7.0s turn window to slide, verify the next frame, and still fire before the 6.35s cutoff.
     */
    fun canAffordAnotherSliderMove(
        nowMs: Long = System.currentTimeMillis(),
        isLiveStreamActive: Boolean
    ): Boolean {
        ensureTurnStarted(nowMs)
        if (_sliderMovesThisTurn >= MAX_BASELINE_PROBES) return false
        val elapsed = elapsedMs(nowMs)
        if (elapsed >= FALLBACK_FIRE_ELAPSED_MS) return false
        val reserve = if (isLiveStreamActive) LIVE_FIRE_RESERVE_MS else A11Y_FIRE_RESERVE_MS
        return remainingMs(nowMs) > reserve
    }

    fun shouldTriggerDeadlineFallback(
        nowMs: Long = System.currentTimeMillis(),
        isLiveStreamActive: Boolean
    ): Boolean {
        return !canAffordAnotherSliderMove(nowMs, isLiveStreamActive)
    }

    fun initializeProbeQueueIfNeeded(spots: List<RankedBaselineSpot>) {
        if (probeQueueInitialized) return
        probeQueueInitialized = true
        rankedProbeQueue.clear()
        rankedProbeQueue.addAll(spots.take(MAX_BASELINE_PROBES))
    }

    fun markSpotRejected(strikerX: Float, reason: String) {
        if (rejectedSpots.none { abs(it.first - strikerX) <= SPOT_MATCH_TOLERANCE_PX }) {
            rejectedSpots.add(strikerX to reason)
        }
    }

    fun nextUntestedProbeSpot(
        currentStrikerX: Float?,
        tolerancePx: Float = SPOT_MATCH_TOLERANCE_PX
    ): RankedBaselineSpot? {
        if (_sliderMovesThisTurn >= MAX_BASELINE_PROBES) return null
        for (spot in rankedProbeQueue) {
            val nearCurrent = currentStrikerX != null && abs(spot.strikerX - currentStrikerX) <= tolerancePx
            val alreadyRejected = rejectedSpots.any { abs(it.first - spot.strikerX) <= tolerancePx }
            val alreadyVisited = visitedStrikerXs.any { abs(it - spot.strikerX) <= tolerancePx }
            if (!nearCurrent && !alreadyRejected && !alreadyVisited) {
                return spot
            }
        }
        return null
    }

    fun hasMoreProbesRemaining(currentStrikerX: Float?): Boolean {
        return _sliderMovesThisTurn < MAX_BASELINE_PROBES &&
            nextUntestedProbeSpot(currentStrikerX) != null
    }

    fun recordSliderMoveDispatched(preStrikerX: Float?) {
        _sliderMovesThisTurn++
        if (preStrikerX != null) {
            visitedStrikerXs.add(preStrikerX)
        }
    }

    fun recordObservation(
        nowMs: Long = System.currentTimeMillis(),
        pieces: List<CarromPiece>,
        striker: CarromPiece?,
        sliderKnob: Point2D?,
        evaluatedSolution: CarromShotSolution?,
        observedSliderGain: Float? = null
    ) {
        ensureTurnStarted(nowMs)
        if (observedSliderGain != null) {
            localSliderGains.add(observedSliderGain.coerceIn(0.65f, 1.45f))
        }
        val nonStriker = pieces.filter { it.color != CoinColor.STRIKER }
        scanFrames.add(
            TurnScanFrame(
                timestampMs = nowMs,
                strikerX = striker?.center?.x,
                sliderKnobX = sliderKnob?.x,
                pieces = nonStriker,
                bestConfidence = evaluatedSolution?.confidenceScore ?: 0f,
                isSureShot = evaluatedSolution?.isSureShot == true
            )
        )
        if (scanFrames.size > MAX_STORED_SCANS) {
            scanFrames.removeAt(0)
        }
        if (striker != null) {
            visitedStrikerXs.add(striker.center.x)
        }
        if (evaluatedSolution != null && !evaluatedSolution.rejectedDueToCollisionOrFoul) {
            val prev = bestEvaluatedThisTurn
            if (prev == null ||
                (evaluatedSolution.isSureShot && !prev.isSureShot) ||
                (evaluatedSolution.isSureShot == prev.isSureShot && evaluatedSolution.confidenceScore > prev.confidenceScore)
            ) {
                bestEvaluatedThisTurn = evaluatedSolution
            }
        }
    }

    fun isSliderStalledOrOscillating(targetStrikerX: Float, currentStrikerX: Float): Boolean {
        if (visitedStrikerXs.size < 2) return false
        val lastPrev = visitedStrikerXs[visitedStrikerXs.size - 2]
        val movedFromPrev = abs(currentStrikerX - lastPrev)
        val stillFarFromTarget = abs(targetStrikerX - currentStrikerX) > 24f
        if (_sliderMovesThisTurn >= 2 && movedFromPrev < 6f && stillFarFromTarget) {
            return true
        }
        if (visitedStrikerXs.size >= 4) {
            val xA = visitedStrikerXs[visitedStrikerXs.size - 1]
            val xC = visitedStrikerXs[visitedStrikerXs.size - 3]
            if (abs(xA - xC) < 12f && abs(xA - targetStrikerX) > 24f) {
                return true
            }
        }
        return false
    }

    fun effectiveTurnSliderGain(persistedGain: Float): Float {
        if (localSliderGains.isEmpty()) return persistedGain
        val localAvg = localSliderGains.average().toFloat()
        return ((persistedGain + localAvg * localSliderGains.size) / (1 + localSliderGains.size))
            .coerceIn(0.65f, 1.45f)
    }

    fun buildConsensusPieces(
        latestPieces: List<CarromPiece>,
        coinRadiusPx: Float = 28f
    ): List<CarromPiece> {
        val latestNonStriker = latestPieces.filter { it.color != CoinColor.STRIKER }
        if (scanFrames.size <= 1) return latestNonStriker

        val matchThreshold = (coinRadiusPx * 1.2f).coerceAtLeast(20f)
        val fused = ArrayList<CarromPiece>(latestNonStriker.size + 4)

        for (piece in latestNonStriker) {
            var sumX = piece.center.x * 1.4f
            var sumY = piece.center.y * 1.4f
            var weight = 1.4f

            for (frame in scanFrames.dropLast(1)) {
                val match = frame.pieces.minByOrNull { prev ->
                    if (prev.color != piece.color) Float.MAX_VALUE
                    else hypot(prev.center.x - piece.center.x, prev.center.y - piece.center.y)
                }
                if (match != null &&
                    match.color == piece.color &&
                    hypot(match.center.x - piece.center.x, match.center.y - piece.center.y) <= matchThreshold
                ) {
                    sumX += match.center.x
                    sumY += match.center.y
                    weight += 1f
                }
            }
            fused.add(piece.copy(center = Point2D(sumX / weight, sumY / weight)))
        }

        if (scanFrames.size >= 3) {
            val candidatePool = scanFrames.dropLast(1).flatMap { it.pieces }
            val visitedExtra = mutableListOf<Point2D>()

            for (cand in candidatePool) {
                val alreadyInFused = fused.any {
                    hypot(it.center.x - cand.center.x, it.center.y - cand.center.y) <= matchThreshold
                }
                val alreadyRecovered = visitedExtra.any {
                    hypot(it.x - cand.center.x, it.y - cand.center.y) <= matchThreshold
                }
                if (alreadyInFused || alreadyRecovered) continue

                var occurrences = 0
                var sumX = 0f
                var sumY = 0f
                for (frame in scanFrames.dropLast(1)) {
                    val m = frame.pieces.firstOrNull { prev ->
                        prev.color == cand.color &&
                            hypot(prev.center.x - cand.center.x, prev.center.y - cand.center.y) <= matchThreshold
                    }
                    if (m != null) {
                        occurrences++
                        sumX += m.center.x
                        sumY += m.center.y
                    }
                }
                if (occurrences >= 2) {
                    val center = Point2D(sumX / occurrences, sumY / occurrences)
                    visitedExtra.add(center)
                    fused.add(CarromPiece(cand.color, center, cand.radiusPx))
                }
            }
        }

        return fused
    }

    /**
     * Deadline fallback (when all <=7 probes fail or clock hits ~8.35s):
     * combines all accumulated Turn Memory frames and fires the safest foul-free pot or cluster break.
     */
    fun solveCombinedFinalShot(
        board: CarromBoardLayout,
        latestPieces: List<CarromPiece>,
        striker: CarromPiece?,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        usePhysicsRollout: Boolean,
        fallbackSolution: CarromShotSolution,
        isFreestyle: Boolean = false
    ): CarromShotSolution {
        val consensusPieces = buildConsensusPieces(latestPieces, board.coinRadiusPx)
        val combinedPieces = if (striker != null) consensusPieces + striker else consensusPieces

        return if (striker != null) {
            CarromGeometrySolver.solveSafestBreakOrPotShot(
                board = board,
                pieces = combinedPieces,
                myCoinColor = myCoinColor,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                existingStriker = striker,
                shotMemory = shotMemory,
                usePhysicsRollout = usePhysicsRollout,
                isFreestyle = isFreestyle
            ) ?: fallbackSolution
        } else {
            fallbackSolution
        }
    }

    fun buildHudState(
        nowMs: Long,
        stage: TurnProbeStage,
        detail: String = ""
    ): CarromTurnHudState {
        return CarromTurnHudState(
            isActive = isTurnActive,
            elapsedSec = elapsedMs(nowMs) / 1000f,
            maxSec = MAX_TURN_BUDGET_MS / 1000f,
            probeNumber = (_sliderMovesThisTurn + 1).coerceAtMost(MAX_BASELINE_PROBES),
            maxProbes = totalPlannedProbes.coerceAtLeast(1).coerceAtMost(MAX_BASELINE_PROBES),
            scanCount = scanCount,
            rejectedCount = rejectedSpots.size,
            stage = stage,
            detail = detail
        )
    }

    fun formatTurnTelemetry(nowMs: Long, sol: CarromShotSolution): String {
        val sec = elapsedMs(nowMs) / 1000f
        val status = when {
            sol.isCollisionAssistedSureShot -> "CAROM-SURE"
            sol.isSureShot -> "SURE"
            sol.isBreakShot -> "BREAK-6.4s"
            else -> "BEST-6.4s"
        }
        return String.format(
            java.util.Locale.US,
            "[7s Clock %.1fs/7.0s | Probe %d/%d (%d rej) | %s]",
            sec,
            (_sliderMovesThisTurn + 1).coerceAtMost(MAX_BASELINE_PROBES),
            totalPlannedProbes.coerceAtLeast(1).coerceAtMost(MAX_BASELINE_PROBES),
            rejectedSpots.size,
            status
        )
    }

    fun resetTurn() {
        turnStartMs = 0L
        scanFrames.clear()
        visitedStrikerXs.clear()
        localSliderGains.clear()
        rankedProbeQueue.clear()
        rejectedSpots.clear()
        probeQueueInitialized = false
        _sliderMovesThisTurn = 0
        bestEvaluatedThisTurn = null
    }
}
