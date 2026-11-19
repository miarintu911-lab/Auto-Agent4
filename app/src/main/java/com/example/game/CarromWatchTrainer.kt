package com.example.game

import com.example.agent.CarromContextHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

object CarromCoachHelper {
    const val CARROM_WATCH_TRAIN_GOAL =
        "Carrom Watch & Learn Training: watch me play live Carrom matches passively with zero touches, learn winning pocket cut angles, striker baseline positions, board friction and power feel from my shots, and save everything into Carrom Memory and Playbook."

    fun isCarromWatchTrainGoal(goal: String): Boolean {
        val trimmed = goal.trim()
        if (trimmed == CARROM_WATCH_TRAIN_GOAL) return true
        if (!CarromContextHelper.isCarromGoalOrApp(trimmed, "", null)) return false
        return trimmed.contains("watch & learn", ignoreCase = true) ||
            trimmed.contains("watch me play", ignoreCase = true) ||
            trimmed.contains("train from my", ignoreCase = true) ||
            trimmed.contains("passive coach", ignoreCase = true) ||
            trimmed.contains("ট্রেনিং") ||
            trimmed.contains("খেলবো") ||
            trimmed.contains("দেখে শিখ") ||
            trimmed.contains("খেলা দেখে")
    }
}

enum class WatchTrainerPhase {
    WAITING_FOR_BOARD,
    ARMED_PRE_SHOT,
    TRACKING_MOTION
}

data class InferredHumanShot(
    val pendingShot: PendingCarromShotRecord,
    val postCounts: BoardPieceCounts,
    val observedPowerHint: Float?,
    val humanInsightSummary: String
)

data class WatchFrameUpdate(
    val phase: WatchTrainerPhase,
    val isBoardVisible: Boolean,
    val hudStatusText: String,
    val completedShot: InferredHumanShot? = null
)

/**
 * Passive Watch & Learn Carrom Trainer.
 *
 * Observes human-played Carrom frames silently (ZERO injected touches/swipes):
 * 1. Arms when the human striker is resting on the bottom baseline (player's turn).
 * 2. Detects baseline slider repositioning and shot release motion.
 * 3. Waits until all discs settle again, then matches pre- vs post-board discs to infer
 *    which coin was aimed at, cut angle, pocket, power style, and pot/foul outcome.
 * 4. Feeds calibrated lessons directly into CarromShotMemoryStore + TaskPlaybook.
 */
class CarromWatchTrainer {

    private var turnInitialStrikerX: Float? = null
    private var preShotObs: CarromWatchObservation? = null
    private var inMotion: Boolean = false
    private var motionStartMs: Long = 0L
    private var maxMotionDiff: Float = 0f
    private var earlyMovedStriker: Point2D? = null
    private var settledStillFrames: Int = 0
    private var missedBoardWhileInMotionFrames: Int = 0

    var shotsObserved: Int = 0
        private set
    var potsObserved: Int = 0
        private set
    var foulsObserved: Int = 0
        private set

    fun reset() {
        turnInitialStrikerX = null
        preShotObs = null
        inMotion = false
        motionStartMs = 0L
        maxMotionDiff = 0f
        earlyMovedStriker = null
        settledStillFrames = 0
        missedBoardWhileInMotionFrames = 0
        shotsObserved = 0
        potsObserved = 0
        foulsObserved = 0
    }

    fun processObservation(
        obs: CarromWatchObservation?,
        myCoinColor: CoinColor,
        isFreestyle: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): WatchFrameUpdate {
        if (obs == null || !obs.isLikelyGameBoard) {
            if (inMotion) {
                missedBoardWhileInMotionFrames++
                if (missedBoardWhileInMotionFrames > 25) {
                    inMotion = false
                    settledStillFrames = 0
                    missedBoardWhileInMotionFrames = 0
                    preShotObs = null
                } else {
                    return WatchFrameUpdate(
                        phase = WatchTrainerPhase.TRACKING_MOTION,
                        isBoardVisible = false,
                        hudStatusText = "Tracking your shot (${shotsObserved} learned)..."
                    )
                }
            }
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = false,
                hudStatusText = if (shotsObserved > 0) {
                    "Learned $shotsObserved shots ($potsObserved pots) — waiting for Carrom board..."
                } else {
                    "Waiting for active Carrom match board (play normally)..."
                }
            )
        }

        missedBoardWhileInMotionFrames = 0

        if (!inMotion) {
            val hasPlayerStrikerOnBaseline = obs.striker != null || obs.sliderKnob != null
            if (!obs.isInMotion && hasPlayerStrikerOnBaseline) {
                val resolvedX = obs.striker?.center?.x ?: obs.sliderKnob?.x
                if (turnInitialStrikerX == null && resolvedX != null) {
                    turnInitialStrikerX = resolvedX
                }
                preShotObs = obs
                val movedFlag = turnInitialStrikerX != null && resolvedX != null &&
                    abs(resolvedX - turnInitialStrikerX!!) > 22f
                val aimNote = if (movedFlag) "slider adjusted" else "ready on baseline"
                return WatchFrameUpdate(
                    phase = WatchTrainerPhase.ARMED_PRE_SHOT,
                    isBoardVisible = true,
                    hudStatusText = "Armed ($aimNote) • $shotsObserved shots / $potsObserved pots learned — take your shot!"
                )
            }

            if (obs.isInMotion && preShotObs != null) {
                inMotion = true
                motionStartMs = nowMs
                maxMotionDiff = obs.lumaDiff
                settledStillFrames = 0
                val preBaselineY = preShotObs!!.board.baselineY
                val coinR = preShotObs!!.board.coinRadiusPx
                earlyMovedStriker = obs.striker?.center?.takeIf { it.y < preBaselineY - coinR * 1.2f }
                return WatchFrameUpdate(
                    phase = WatchTrainerPhase.TRACKING_MOTION,
                    isBoardVisible = true,
                    hudStatusText = "Shot in motion — observing trajectory & impact..."
                )
            }

            // Opponent turn (bottom striker absent and board calm)
            turnInitialStrikerX = null
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = true,
                hudStatusText = "Opponent turn / waiting for your striker ($shotsObserved shots learned)..."
            )
        }

        // Currently tracking shot motion
        maxMotionDiff = maxOf(maxMotionDiff, obs.lumaDiff)
        val pre = preShotObs
        if (pre != null && earlyMovedStriker == null && obs.striker != null) {
            val s = obs.striker.center
            if (s.y < pre.board.baselineY - pre.board.coinRadiusPx * 1.2f) {
                earlyMovedStriker = s
            }
        }

        val elapsedMotionMs = nowMs - motionStartMs
        if (!obs.isInMotion) {
            settledStillFrames++
        } else {
            settledStillFrames = 0
        }

        val readyToEvaluate = (settledStillFrames >= 2) ||
            (settledStillFrames >= 1 && elapsedMotionMs > 2_200L) ||
            (elapsedMotionMs > 7_500L && pre != null)

        if (!readyToEvaluate) {
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.TRACKING_MOTION,
                isBoardVisible = true,
                hudStatusText = "Coins moving — waiting for board to settle..."
            )
        }

        inMotion = false
        settledStillFrames = 0
        val preSnapshot = preShotObs
        preShotObs = if (obs.striker != null || obs.sliderKnob != null) obs else null

        if (preSnapshot == null) {
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = true,
                hudStatusText = "Waiting for next turn ($shotsObserved shots learned)..."
            )
        }

        val inferred = inferHumanShotFromFrames(
            preShot = preSnapshot,
            postShot = obs,
            earlyMovedStriker = earlyMovedStriker,
            turnInitialStrikerX = turnInitialStrikerX,
            maxMotionDiff = maxMotionDiff,
            myCoinColor = myCoinColor,
            isFreestyle = isFreestyle,
            nowMs = nowMs
        )
        earlyMovedStriker = null

        if (inferred == null) {
            // False motion spike (e.g., UI ripple/banner without moving coins or firing striker)
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.ARMED_PRE_SHOT,
                isBoardVisible = true,
                hudStatusText = "Ready on baseline ($shotsObserved shots learned) — take your shot!"
            )
        }

        shotsObserved++
        val preCounts = inferred.pendingShot.preCounts
        val postCounts = inferred.postCounts
        val preTotal = preCounts.myCount + preCounts.opponentCount + preCounts.queenCount
        val postTotal = postCounts.myCount + postCounts.opponentCount + postCounts.queenCount
        val isFoul = if (isFreestyle) {
            postTotal > preTotal
        } else {
            (postCounts.myCount > preCounts.myCount) ||
                (postCounts.opponentCount < preCounts.opponentCount && postCounts.myCount >= preCounts.myCount && !(preCounts.queenCount > 0 && postCounts.queenCount == 0))
        }
        val isPot = !isFoul && (
            (preCounts.queenCount > 0 && postCounts.queenCount == 0) ||
                postCounts.myCount < preCounts.myCount ||
                (isFreestyle && postCounts.opponentCount < preCounts.opponentCount)
            )
        if (isFoul) foulsObserved++ else if (isPot) potsObserved++

        // Reset initial striker X if turn stays ours cleanly so next slide is measured fresh
        turnInitialStrikerX = obs.striker?.center?.x ?: obs.sliderKnob?.x

        return WatchFrameUpdate(
            phase = if (obs.striker != null || obs.sliderKnob != null) WatchTrainerPhase.ARMED_PRE_SHOT else WatchTrainerPhase.WAITING_FOR_BOARD,
            isBoardVisible = true,
            hudStatusText = "Learned #$shotsObserved ($potsObserved pots, $foulsObserved fouls): ${inferred.humanInsightSummary}",
            completedShot = inferred
        )
    }

    companion object {

        fun inferHumanShotFromFrames(
            preShot: CarromWatchObservation,
            postShot: CarromWatchObservation,
            earlyMovedStriker: Point2D? = null,
            turnInitialStrikerX: Float? = null,
            maxMotionDiff: Float = 18f,
            myCoinColor: CoinColor,
            isFreestyle: Boolean,
            nowMs: Long = System.currentTimeMillis()
        ): InferredHumanShot? {
            val board = preShot.board
            if (board.pockets.size < 4) return null

            val preStrikerPos = preShot.striker?.center
                ?: preShot.sliderKnob?.let { Point2D(it.x, board.baselineY) }
                ?: return null

            val preCoins = preShot.pieces.filter { it.color != CoinColor.STRIKER }
            val postCoins = postShot.pieces.filter { it.color != CoinColor.STRIKER }
            if (preCoins.isEmpty()) return null

            val preCounts = CarromShotMemoryStore.countBoardPieces(preCoins, myCoinColor)
            val postCounts = CarromShotMemoryStore.countBoardPieces(postCoins, myCoinColor)

            // Match each pre-coin greedily to its closest post-coin of the same color
            val unmatchedPost = postCoins.toMutableList()
            data class CoinTransition(
                val preCoin: CarromPiece,
                val matchedPost: CarromPiece?,
                val displacementPx: Float
            )

            val pairs = mutableListOf<Pair<CarromPiece, CarromPiece>>()
            val remainingPre = preCoins.toMutableList()
            while (remainingPre.isNotEmpty()) {
                var bestPreIdx = -1
                var bestPostIdx = -1
                var bestDist = Float.MAX_VALUE
                for (i in remainingPre.indices) {
                    val p = remainingPre[i]
                    for (j in unmatchedPost.indices) {
                        val q = unmatchedPost[j]
                        if (p.color != q.color) continue
                        val d = (q.center - p.center).length()
                        if (d < bestDist) {
                            bestDist = d
                            bestPreIdx = i
                            bestPostIdx = j
                        }
                    }
                }
                if (bestPreIdx < 0 || bestPostIdx < 0) break
                val preMatch = remainingPre.removeAt(bestPreIdx)
                val postMatch = unmatchedPost.removeAt(bestPostIdx)
                pairs.add(preMatch to postMatch)
            }

            val transitions = mutableListOf<CoinTransition>()
            for ((preC, postC) in pairs) {
                transitions.add(CoinTransition(preC, postC, (postC.center - preC.center).length()))
            }
            for (vanished in remainingPre) {
                transitions.add(CoinTransition(vanished, null, board.width * 0.65f))
            }

            val moveThreshold = board.coinRadiusPx * 0.55f
            val displacedCoins = transitions.filter { it.matchedPost == null || it.displacementPx >= moveThreshold }
            val maxDisp = transitions.maxOfOrNull { it.displacementPx } ?: 0f
            val countChanged = preCounts != postCounts

            // Filter false motion spikes (e.g., toast/banner flash with zero real shot)
            val strikerStillAtSameSpot = postShot.striker != null &&
                abs(postShot.striker.center.x - preStrikerPos.x) < 22f
            if (!countChanged && displacedCoins.isEmpty() && strikerStillAtSameSpot) {
                return null
            }

            // Detect central rack break shot
            val boardCenter = Point2D((board.left + board.right) * 0.5f, (board.top + board.bottom) * 0.5f)
            val centralPreClusterCount = preCoins.count { (it.center - boardCenter).length() <= board.coinRadiusPx * 3.8f }
            val isBreakShot = (centralPreClusterCount >= 5 && displacedCoins.size >= 4) || displacedCoins.size >= 6

            // Infer which target coin the human shot at
            val vanishedCoins = transitions.filter { it.matchedPost == null }.map { it.preCoin }
            val legalColorFilter: (CarromPiece) -> Boolean = { c ->
                isFreestyle || c.color == myCoinColor || c.color == CoinColor.QUEEN
            }

            val earlyShotDir = earlyMovedStriker
                ?.takeIf { (it - preStrikerPos).length() > board.strikerRadiusPx }
                ?.let { (it - preStrikerPos).normalized() }

            val targetCoin: CarromPiece = when {
                vanishedCoins.any(legalColorFilter) -> {
                    val candidates = vanishedCoins.filter(legalColorFilter)
                    if (earlyShotDir != null) {
                        candidates.minByOrNull { c -> rayPointDistance(preStrikerPos, earlyShotDir, c.center) }
                            ?: candidates.first()
                    } else {
                        candidates.minByOrNull { c ->
                            board.pockets.minOf { p -> (p - c.center).length() }
                        } ?: candidates.first()
                    }
                }
                vanishedCoins.isNotEmpty() -> vanishedCoins.first()
                earlyShotDir != null -> {
                    preCoins.minByOrNull { c ->
                        val along = (c.center - preStrikerPos).dot(earlyShotDir)
                        if (along <= 0f) Float.MAX_VALUE
                        else rayPointDistance(preStrikerPos, earlyShotDir, c.center)
                    } ?: preCoins.first()
                }
                displacedCoins.any { legalColorFilter(it.preCoin) } -> {
                    displacedCoins
                        .filter { legalColorFilter(it.preCoin) }
                        .maxByOrNull { it.displacementPx }
                        ?.preCoin ?: preCoins.first()
                }
                displacedCoins.isNotEmpty() -> {
                    displacedCoins.maxByOrNull { it.displacementPx }!!.preCoin
                }
                else -> preCoins.filter(legalColorFilter).minByOrNull { (it.center - preStrikerPos).length() }
                    ?: preCoins.first()
            }

            // Infer pocket aimed at from striker -> targetCoin -> pocket geometry
            val strikerToTarget = (targetCoin.center - preStrikerPos).normalized()
            var bestPocketIdx = 0
            var bestCutDeg = 45f
            var bestPocketScore = Float.POSITIVE_INFINITY
            board.pockets.forEachIndexed { idx, pocket ->
                val toPocket = pocket - targetCoin.center
                val distPocket = toPocket.length().coerceAtLeast(1f)
                val dirCp = toPocket.normalized()
                val aimRefDir = earlyShotDir ?: strikerToTarget
                val cosTheta = aimRefDir.dot(dirCp).coerceIn(-1f, 1f)
                val angleDeg = (acos(cosTheta) * (180.0 / PI)).toFloat()
                // Favor pockets roughly forward of striker->coin line & close when vanished
                val distBias = (distPocket / board.width) * 14f
                val composite = angleDeg + distBias
                if (composite < bestPocketScore) {
                    bestPocketScore = composite
                    bestPocketIdx = idx
                    bestCutDeg = angleDeg.coerceIn(0f, 68f)
                }
            }

            val dispRatio = (maxDisp / board.width).coerceIn(0f, 1.5f)
            val powerStyle = when {
                isBreakShot || displacedCoins.size >= 4 || dispRatio >= 0.42f || maxMotionDiff >= 28f ->
                    CarromPowerStyle.HARD
                dispRatio < 0.16f && displacedCoins.size <= 2 && maxMotionDiff < 19f ->
                    CarromPowerStyle.SOFT
                else -> CarromPowerStyle.MEDIUM
            }

            val preTotalCoins = preCounts.myCount + preCounts.opponentCount + preCounts.queenCount
            val postTotalCoins = postCounts.myCount + postCounts.opponentCount + postCounts.queenCount
            val cleanPotOccurred = if (isFreestyle) {
                postTotalCoins < preTotalCoins
            } else {
                (postCounts.myCount < preCounts.myCount || postCounts.queenCount < preCounts.queenCount) &&
                    postCounts.myCount <= preCounts.myCount
            }
            val observedPowerHint = when {
                isBreakShot -> null
                countChanged && cleanPotOccurred -> when (powerStyle) {
                    CarromPowerStyle.SOFT -> 0.89f
                    CarromPowerStyle.MEDIUM -> 1.00f
                    CarromPowerStyle.HARD -> 1.12f
                }
                else -> null
            }

            val strikerNormX = ((preStrikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
            val movedSlider = turnInitialStrikerX != null && abs(preStrikerPos.x - turnInitialStrikerX) > 22f
            val (coinRowZone, coinColZone) = CarromMemoryData.coinZoneFor(targetCoin.center, board)

            val pending = PendingCarromShotRecord(
                myCoinColor = myCoinColor,
                targetCoinColor = targetCoin.color,
                pocketIndex = bestPocketIdx,
                cutAngleDeg = bestCutDeg,
                strikerNormalizedX = strikerNormX,
                coinRowZone = coinRowZone,
                coinColZone = coinColZone,
                powerStyle = powerStyle,
                movedStrikerWithSlider = movedSlider,
                isBankShot = false,
                isComboShot = false,
                isBreakShot = isBreakShot,
                preCounts = preCounts,
                isFreestyle = isFreestyle,
                timestampMs = nowMs
            )

            val pocketLabel = CarromMemoryData.pocketName(bestPocketIdx)
            val slideLabel = if (movedSlider) "Slider moved" else "In-place"
            val insight = "${targetCoin.color.name} -> $pocketLabel (cut=${bestCutDeg.toInt()}°, ${powerStyle.label}, $slideLabel)"

            return InferredHumanShot(
                pendingShot = pending,
                postCounts = postCounts,
                observedPowerHint = observedPowerHint,
                humanInsightSummary = insight
            )
        }

        private fun rayPointDistance(origin: Point2D, unitDir: Point2D, point: Point2D): Float {
            val v = point - origin
            val t = v.dot(unitDir).coerceAtLeast(0f)
            val closest = Point2D(origin.x + unitDir.x * t, origin.y + unitDir.y * t)
            return (point - closest).length()
        }
    }
}
