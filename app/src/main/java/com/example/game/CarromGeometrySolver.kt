package com.example.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class CoinColor {
    WHITE,
    BLACK,
    QUEEN,
    STRIKER
}

data class Point2D(
    val x: Float,
    val y: Float
) {
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Float): Point2D = Point2D(x * scalar, y * scalar)

    fun length(): Float = hypot(x, y)

    fun normalized(): Point2D {
        val len = length()
        return if (len > 1e-4f) Point2D(x / len, y / len) else Point2D(0f, 0f)
    }

    fun dot(other: Point2D): Float = x * other.x + y * other.y
}

data class CarromPiece(
    val color: CoinColor,
    val center: Point2D,
    val radiusPx: Float
)

data class CarromBoardLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pockets: List<Point2D>,
    val baselineY: Float,
    val coinRadiusPx: Float,
    val strikerRadiusPx: Float,
    val pocketRadiusPx: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

data class CarromShotSolution(
    val strikerPos: Point2D,
    val dragEnd: Point2D,
    val ghostBall: Point2D,
    val targetCoin: CarromPiece,
    val targetPocket: Point2D,
    val pocketIndex: Int = 0,
    val cutAngleDeg: Float,
    val isBankShot: Boolean,
    val isComboShot: Boolean = false,
    val isBreakShot: Boolean = false,
    val isSureShot: Boolean = false,
    val isCollisionAssistedSureShot: Boolean = false,
    val rejectedDueToCollisionOrFoul: Boolean = false,
    val powerStyle: CarromPowerStyle = CarromPowerStyle.MEDIUM,
    val confidenceScore: Float,
    val reason: String
)

/**
 * Pure Kotlin Ghost-Ball & Vector Physics Carrom Shot Solver.
 * Computes exact striker placement, cut angles, obstacle collision checks,
 * striker scratch-foul guards, 1-cushion bank/rebound, 2-coin combo, cluster break,
 * and adapts power & pocket choices from CarromMemoryData.
 */
object CarromGeometrySolver {

    fun solveBestShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece? = null,
        canRepositionStriker: Boolean = true,
        shotMemory: CarromMemoryData? = null,
        usePhysicsRollout: Boolean = true,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        if (board.pockets.size < 4) return null

        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else {
            pieces.filter {
                it.color == myCoinColor || it.color == CoinColor.QUEEN
            }.ifEmpty {
                pieces.filter { it.color != CoinColor.STRIKER }
            }
        }
        if (targetPieces.isEmpty()) return null

        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val strikerCandidates = buildStrikerCandidates(
            board = board,
            obstacles = obstacles,
            existingStriker = existingStriker,
            canRepositionStriker = canRepositionStriker
        )
        if (strikerCandidates.isEmpty()) return null

        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true
        val inPlaceAdj = shotMemory?.placementStats?.inPlaceBonusAdjustment() ?: 0f
        val directPool = mutableListOf<RolloutCandidate>()
        var bestDirect: CarromShotSolution? = null

        for (coin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val coinToPocket = (pocket - coin.center)
                val distCoinPocket = coinToPocket.length()
                if (distCoinPocket < board.coinRadiusPx * 1.15f) return@forEachIndexed

                val dirCp = coinToPocket.normalized()
                // Ghost-ball position behind the target coin along pocket line
                val impactDist = board.coinRadiusPx + board.strikerRadiusPx
                val ghostBall = coin.center - dirCp * impactDist

                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) {
                    return@forEachIndexed
                }

                // Verify coin -> pocket path is clear of blocking coins
                if (!isSegmentClear(
                        start = coin.center,
                        end = pocket,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx * 1.85f
                    )
                ) {
                    return@forEachIndexed
                }

                for (strikerPos in strikerCandidates) {
                    val strikerToGhost = ghostBall - strikerPos
                    val distStrikerGhost = strikerToGhost.length()
                    if (distStrikerGhost < board.strikerRadiusPx * 1.1f) continue

                    val dirSg = strikerToGhost.normalized()
                    val cosAngle = dirSg.dot(dirCp).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(cosAngle) * (180.0 / PI)).toFloat()

                    // Physically impossible or ultra-thin unreliable cut shots (> 64 degrees)
                    if (cutAngleDeg > 64f) continue

                    // Verify striker -> ghostBall path does not clip another coin first
                    if (!isSegmentClear(
                            start = strikerPos,
                            end = ghostBall,
                            obstacles = obstacles,
                            excluded = coin,
                            clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                        )
                    ) {
                        continue
                    }

                    // Foul guard: avoid shots where striker follows straight into target or adjacent pocket
                    if (willStrikerScratchIntoPocket(
                            strikerPos = strikerPos,
                            ghostBall = ghostBall,
                            dirSg = dirSg,
                            dirCp = dirCp,
                            cutAngleDeg = cutAngleDeg,
                            board = board,
                            extraSafetyMargin = extraScratchGuard
                        )
                    ) {
                        continue
                    }

                    val isHanger = distCoinPocket <= board.coinRadiusPx * 5.2f
                    val hangerBonus = if (isHanger) 0.16f else 0f
                    val queenBonus = if (coin.color == CoinColor.QUEEN) 0.09f else 0f
                    val angleScore = 1f - (cutAngleDeg / 66f) * 0.55f
                    val distScore = 1f - ((distCoinPocket + distStrikerGhost) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
                    val isCurrentStrikerPos = existingStriker != null && abs(strikerPos.x - existingStriker.center.x) < 18f
                    val existingBonus = if (isCurrentStrikerPos) (0.14f + inPlaceAdj).coerceIn(0.02f, 0.28f) else 0f
                    val memoryBias = shotMemory?.confidenceBiasFor(pocketIdx, cutAngleDeg) ?: 0f
                    val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
                    val heatmapPenalty = (shotMemory?.scratchHeatmap?.riskFor(pocketIdx, cutAngleDeg, normStrikerX) ?: 0f) * 0.35f
                    val score = (angleScore * 0.65f + distScore * 0.35f + queenBonus + hangerBonus + existingBonus + memoryBias - heatmapPenalty)
                        .coerceIn(0.05f, 1f)

                    val learnedPowerScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutAngleDeg) ?: 1.0f
                    val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
                    val baseMult = if (isHanger) 0.85f else 1.0f
                    val effectiveMult = baseMult * learnedPowerScale * matchPowerGain

                    directPool.add(
                        RolloutCandidate(
                            strikerPos = strikerPos,
                            baseDir = dirSg,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            isBankShot = false,
                            isComboShot = false,
                            isBreakShot = false,
                            basePullMultiplier = baseMult * learnedPowerScale,
                            staticScore = score,
                            totalDistance = distStrikerGhost + distCoinPocket
                        )
                    )

                    if (bestDirect == null || score > bestDirect.confidenceScore) {
                        val powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
                        val dragEnd = computeSlingshotDragEnd(
                            strikerPos = strikerPos,
                            shotDir = dirSg,
                            totalDistance = distStrikerGhost + distCoinPocket,
                            boardWidth = board.width,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            cutAngleDeg = cutAngleDeg,
                            powerMultiplier = effectiveMult
                        )
                        val shotLabel = if (isHanger) "Hanger" else "Direct"
                        val pocketLabel = CarromMemoryData.pocketName(pocketIdx)
                        bestDirect = CarromShotSolution(
                            strikerPos = strikerPos,
                            dragEnd = dragEnd,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            isBankShot = false,
                            powerStyle = powerStyle,
                            confidenceScore = score,
                            reason = "$shotLabel ${coin.color.name} (${powerStyle.label}) cut=${cutAngleDeg.toInt()}° -> $pocketLabel"
                        )
                    }
                }
            }
        }

        var directRejectedByPhysics = false
        if (usePhysicsRollout && directPool.isNotEmpty() && (bestDirect == null || bestDirect.confidenceScore >= 0.52f)) {
            val verified = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = directPool,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verified != null && (verified.confidenceScore >= 0.56f || bestDirect == null)) {
                return verified
            }
            if (verified == null) {
                directRejectedByPhysics = true
            }
        }

        if (!directRejectedByPhysics && bestDirect != null && bestDirect.confidenceScore >= 0.58f) {
            return bestDirect
        }

        // Fallback 1: check 1-cushion bank/rebound shot off top/left/right/bottom rails
        val bankPool = mutableListOf<RolloutCandidate>()
        val bestBank = solveOneRailBankShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            outCandidates = bankPool
        )

        if (usePhysicsRollout && (directPool.isNotEmpty() || bankPool.isNotEmpty())) {
            val combined = directPool + bankPool
            val verifiedBankOrDirect = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = combined,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verifiedBankOrDirect != null && verifiedBankOrDirect.confidenceScore >= 0.52f) {
                return verifiedBankOrDirect
            }
        }

        val bestDirectOrBank = when {
            directRejectedByPhysics && bestBank == null -> null
            directRejectedByPhysics -> bestBank
            bestDirect != null && bestBank != null ->
                if (bestDirect.confidenceScore >= bestBank.confidenceScore) bestDirect else bestBank
            else -> bestDirect ?: bestBank
        }
        if (!usePhysicsRollout && bestDirectOrBank != null && bestDirectOrBank.confidenceScore >= 0.52f) {
            return bestDirectOrBank
        }

        // Fallback 2: 2-coin Combination (kiss/carom) shot: Striker -> Helper Target Coin -> Final Coin -> Pocket
        val comboPool = mutableListOf<RolloutCandidate>()
        val bestCombo = solveTwoCoinComboShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            outCandidates = comboPool
        )
        if (usePhysicsRollout && comboPool.isNotEmpty()) {
            val verifiedCombo = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = directPool + comboPool + bankPool,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verifiedCombo != null && (verifiedCombo.isSureShot || verifiedCombo.confidenceScore >= 0.50f)) {
                return verifiedCombo
            }
        }
        if (!usePhysicsRollout) {
            if (bestCombo != null && (bestDirectOrBank == null || bestCombo.confidenceScore > bestDirectOrBank.confidenceScore)) {
                return bestCombo
            }
            if (bestDirectOrBank != null) {
                return bestDirectOrBank
            }
        }

        // Fallback 3: Physics-verified zero-foul Break / Cluster-Scatter shot
        return solveClusterBreakShot(
            board = board,
            pieces = pieces,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        )
    }

    /**
     * Full-board pre-scan step: evaluates up to 25 baseline striker X coordinates
     * using 2D physics rollouts across direct, combo, and 1-rail bank paths,
     * returning the top [maxProbes] (default 6) distinct baseline X positions.
     */
    fun buildRankedBaselineProbeQueue(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        existingStriker: CarromPiece?,
        shotMemory: CarromMemoryData?,
        maxProbes: Int = CarromTurnMemory.MAX_BASELINE_PROBES,
        isFreestyle: Boolean = false
    ): List<RankedBaselineSpot> {
        if (board.pockets.size < 4) return emptyList()
        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else {
            pieces.filter {
                it.color == myCoinColor || it.color == CoinColor.QUEEN
            }.ifEmpty {
                pieces.filter { it.color != CoinColor.STRIKER }
            }
        }
        if (targetPieces.isEmpty()) return emptyList()

        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val strikerCandidates = buildStrikerCandidates(
            board = board,
            obstacles = obstacles,
            existingStriker = existingStriker,
            canRepositionStriker = true
        )
        if (strikerCandidates.isEmpty()) return emptyList()

        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true
        val pool = mutableListOf<RolloutCandidate>()

        for (coin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val coinToPocket = pocket - coin.center
                val distCoinPocket = coinToPocket.length()
                if (distCoinPocket < board.coinRadiusPx * 1.15f) return@forEachIndexed

                val dirCp = coinToPocket.normalized()
                val ghostBall = coin.center - dirCp * (board.coinRadiusPx + board.strikerRadiusPx)
                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) {
                    return@forEachIndexed
                }

                val clearCoinToPocket = isSegmentClear(
                    start = coin.center,
                    end = pocket,
                    obstacles = obstacles,
                    excluded = coin,
                    clearanceRadius = board.coinRadiusPx * 1.75f
                )

                for (strikerPos in strikerCandidates) {
                    val sg = ghostBall - strikerPos
                    val distSg = sg.length()
                    if (distSg < board.strikerRadiusPx * 1.1f) continue
                    val dirSg = sg.normalized()
                    val cutDeg = (acos(dirSg.dot(dirCp).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                    if (cutDeg > 64f) continue

                    if (willStrikerScratchIntoPocket(strikerPos, ghostBall, dirSg, dirCp, cutDeg, board, extraScratchGuard)) {
                        continue
                    }

                    // Allow slightly obstructed lines into physics pool so valid carom/collision-pots can be discovered
                    val clearStrikerToGhost = isSegmentClear(
                        start = strikerPos,
                        end = ghostBall,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.75f
                    )
                    if (!clearCoinToPocket && !clearStrikerToGhost) continue

                    val isHanger = distCoinPocket <= board.coinRadiusPx * 5.2f
                    val obstructionBias = if (clearCoinToPocket && clearStrikerToGhost) 0f else -0.18f
                    val queenBonus = if (coin.color == CoinColor.QUEEN) 0.09f else 0f
                    val hangerBonus = if (isHanger) 0.14f else 0f
                    val angleScore = 1f - (cutDeg / 66f) * 0.55f
                    val distScore = 1f - ((distCoinPocket + distSg) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
                    val score = (angleScore * 0.65f + distScore * 0.35f + queenBonus + hangerBonus + obstructionBias)
                        .coerceIn(0.05f, 1f)
                    val learnedScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutDeg) ?: 1.0f
                    val baseMult = (if (isHanger) 0.85f else 1.0f) * learnedScale

                    pool.add(
                        RolloutCandidate(
                            strikerPos = strikerPos,
                            baseDir = dirSg,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            isBankShot = false,
                            isComboShot = !clearStrikerToGhost || !clearCoinToPocket,
                            isBreakShot = false,
                            basePullMultiplier = baseMult,
                            staticScore = score,
                            totalDistance = distSg + distCoinPocket
                        )
                    )
                }
            }
        }

        // Also include 2-coin combo & 1-rail bank options in probe queue
        solveTwoCoinComboShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = 1080,
            screenHeight = 2400,
            shotMemory = shotMemory,
            outCandidates = pool
        )
        solveOneRailBankShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = 1080,
            screenHeight = 2400,
            shotMemory = shotMemory,
            outCandidates = pool
        )

        return CarromPhysicsSimulator.rankTopBaselinePositions(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            candidates = pool,
            shotMemory = shotMemory,
            matchCalibration = shotMemory?.matchCalibration,
            maxSpots = maxProbes,
            isFreestyle = isFreestyle
        )
    }

    /**
     * Deadline fallback (used at ~8.3–8.5s if zero pure Sure Shots were verified):
     * returns the highest-scoring foul-free pot attempt, or a guaranteed cluster break shot.
     */
    fun solveSafestBreakOrPotShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece?,
        shotMemory: CarromMemoryData?,
        usePhysicsRollout: Boolean = true,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        val inPlaceAttempt = solveBestShot(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = existingStriker,
            canRepositionStriker = false,
            shotMemory = shotMemory,
            usePhysicsRollout = usePhysicsRollout,
            isFreestyle = isFreestyle
        )
        if (inPlaceAttempt != null && !inPlaceAttempt.rejectedDueToCollisionOrFoul) {
            return inPlaceAttempt
        }
        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else {
            pieces.filter { it.color == myCoinColor || it.color == CoinColor.QUEEN }
                .ifEmpty { pieces.filter { it.color != CoinColor.STRIKER } }
        }
        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val candidates = buildStrikerCandidates(board, obstacles, existingStriker, canRepositionStriker = false)
        return solveClusterBreakShot(
            board = board,
            pieces = pieces,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = candidates,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        ) ?: inPlaceAttempt
    }

    private fun refineWithPhysicsRollout(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        candidates: List<RolloutCandidate>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        val (winner, sim) = CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            candidates = candidates,
            shotMemory = shotMemory,
            matchCalibration = shotMemory?.matchCalibration,
            isFreestyle = isFreestyle
        ) ?: return null

        // Do not accept a rollout winner if it explicitly predicts a striker scratch or (in Disc Pool) opponent-only pot
        if (sim.predictedScratchFoul || (!isFreestyle && sim.predictedOpponentOnlyFoul)) {
            return null
        }

        val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
        val effectiveMult = winner.basePullMultiplier.let {
            ((sim.verifiedPullMultiplier * 0.7f + it * 0.3f) * matchPowerGain).coerceIn(0.72f, 1.48f)
        }
        val powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
        val dragEnd = computeSlingshotDragEnd(
            strikerPos = winner.strikerPos,
            shotDir = sim.verifiedDir,
            totalDistance = winner.totalDistance,
            boardWidth = board.width,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cutAngleDeg = winner.cutAngleDeg,
            powerMultiplier = effectiveMult
        )
        val kindTag = when {
            winner.isBreakShot -> "Break"
            sim.isCollisionAssistedSureShot -> "CAROM-SURE"
            sim.isSureShot -> "SURE"
            winner.isComboShot -> "Combo"
            winner.isBankShot -> "1-Rail Bank"
            else -> "2D-Sim"
        }
        val kissTag = when {
            sim.isCollisionAssistedSureShot -> ", carom-pot"
            sim.predictedKissBlock -> ", kiss-miss"
            else -> ", clean"
        }
        val pocketLabel = CarromMemoryData.pocketName(winner.pocketIndex)
        val robPct = (sim.robustnessScore * 100).toInt()
        return CarromShotSolution(
            strikerPos = winner.strikerPos,
            dragEnd = dragEnd,
            ghostBall = winner.ghostBall,
            targetCoin = winner.targetCoin,
            targetPocket = winner.targetPocket,
            pocketIndex = winner.pocketIndex,
            cutAngleDeg = winner.cutAngleDeg,
            isBankShot = winner.isBankShot,
            isComboShot = winner.isComboShot,
            isBreakShot = winner.isBreakShot,
            isSureShot = sim.isSureShot,
            isCollisionAssistedSureShot = sim.isCollisionAssistedSureShot,
            rejectedDueToCollisionOrFoul = sim.rejectedDueToCollisionOrFoul,
            powerStyle = powerStyle,
            confidenceScore = if (sim.predictedKissBlock) {
                (sim.physicsCompositeScore * 0.72f).coerceAtLeast(0.10f)
            } else {
                sim.physicsCompositeScore
            },
            reason = "$kindTag ${winner.targetCoin.color.name} (${powerStyle.label}) cut=${winner.cutAngleDeg.toInt()}° -> $pocketLabel [Sim ${sim.rolloutsRun}x/${sim.elapsedMs}ms, rob=${robPct}%$kissTag]"
        )
    }

    private fun solveOneRailBankShot(
        board: CarromBoardLayout,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        outCandidates: MutableList<RolloutCandidate>? = null
    ): CarromShotSolution? {
        var bestBank: CarromShotSolution? = null
        val r = board.coinRadiusPx
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        // Mirror top, left, right, and bottom rails for 1-cushion bank geometry
        val railPlanes = listOf(
            "top" to { p: Point2D -> Point2D(p.x, 2f * (board.top + r) - p.y) },
            "left" to { p: Point2D -> Point2D(2f * (board.left + r) - p.x, p.y) },
            "right" to { p: Point2D -> Point2D(2f * (board.right - r) - p.x, p.y) },
            "bottom" to { p: Point2D -> Point2D(p.x, 2f * (board.bottom - r) - p.y) }
        )

        for (coin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                for ((railName, mirrorFn) in railPlanes) {
                    val mirroredPocket = mirrorFn(pocket)
                    val toMirror = (mirroredPocket - coin.center)
                    if (toMirror.length() < r * 2f) continue
                    val dirBank = toMirror.normalized()
                    val ghostBall = coin.center - dirBank * (board.coinRadiusPx + board.strikerRadiusPx)
                    if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx)) continue

                    for (strikerPos in strikerCandidates) {
                        val sg = ghostBall - strikerPos
                        if (sg.length() < r * 1.5f) continue
                        val dirSg = sg.normalized()
                        val cutDeg = (acos(dirSg.dot(dirBank).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                        if (cutDeg > 38f) continue

                        if (!isSegmentClear(strikerPos, ghostBall, obstacles, coin, r + board.strikerRadiusPx * 0.9f)) {
                            continue
                        }
                        if (willStrikerScratchIntoPocket(strikerPos, ghostBall, dirSg, dirBank, cutDeg, board, extraScratchGuard)) {
                            continue
                        }

                        val memoryBias = shotMemory?.confidenceBiasFor(pocketIdx, cutDeg) ?: 0f
                        val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
                        val heatmapPenalty = (shotMemory?.scratchHeatmap?.riskFor(pocketIdx, cutDeg, normStrikerX) ?: 0f) * 0.25f
                        val score = (0.64f - (cutDeg / 60f) * 0.25f + memoryBias * 0.6f - heatmapPenalty).coerceIn(0.05f, 0.72f)
                        val learnedScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutDeg) ?: 1.0f
                        val effectiveMult = 1.24f * learnedScale

                        outCandidates?.add(
                            RolloutCandidate(
                                strikerPos = strikerPos,
                                baseDir = dirSg,
                                ghostBall = ghostBall,
                                targetCoin = coin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                isComboShot = false,
                                isBreakShot = false,
                                basePullMultiplier = effectiveMult,
                                staticScore = score,
                                totalDistance = sg.length() + toMirror.length()
                            )
                        )

                        if (bestBank == null || score > bestBank.confidenceScore) {
                            val dragEnd = computeSlingshotDragEnd(
                                strikerPos = strikerPos,
                                shotDir = dirSg,
                                totalDistance = sg.length() + toMirror.length(),
                                boardWidth = board.width,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                cutAngleDeg = cutDeg,
                                powerMultiplier = effectiveMult // Compensate rail restitution damping
                            )
                            bestBank = CarromShotSolution(
                                strikerPos = strikerPos,
                                dragEnd = dragEnd,
                                ghostBall = ghostBall,
                                targetCoin = coin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult),
                                confidenceScore = score,
                                reason = "1-rail ($railName) bank ${coin.color.name} -> ${CarromMemoryData.pocketName(pocketIdx)} cut=${cutDeg.toInt()}°"
                            )
                        }
                    }
                }
            }
        }
        return bestBank
    }

    private fun solveTwoCoinComboShot(
        board: CarromBoardLayout,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        outCandidates: MutableList<RolloutCandidate>? = null
    ): CarromShotSolution? {
        var bestCombo: CarromShotSolution? = null
        val coinImpactDist = board.coinRadiusPx * 2.0f
        val strikerImpactDist = board.coinRadiusPx + board.strikerRadiusPx
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        for (finalCoin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val toPocket = pocket - finalCoin.center
                val distPocket = toPocket.length()
                if (distPocket < board.coinRadiusPx * 1.2f) return@forEachIndexed
                val dirFp = toPocket.normalized()
                if (!isSegmentClear(finalCoin.center, pocket, obstacles, finalCoin, board.coinRadiusPx * 1.85f)) {
                    return@forEachIndexed
                }

                // Required contact point for helper coin to drive finalCoin into pocket
                val helperGhost = finalCoin.center - dirFp * coinImpactDist
                if (!isInsidePlayableBoard(helperGhost, board, margin = board.coinRadiusPx)) return@forEachIndexed

                for (helperCoin in targetPieces) {
                    if (helperCoin === finalCoin) continue
                    val helperToGhost = helperGhost - helperCoin.center
                    val distHelper = helperToGhost.length()
                    if (distHelper < board.coinRadiusPx * 0.5f || distHelper > board.width * 0.55f) continue

                    val dirHg = helperToGhost.normalized()
                    val comboCutDeg = (acos(dirHg.dot(dirFp).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                    if (comboCutDeg > 34f) continue

                    if (!isSegmentClear(
                            start = helperCoin.center,
                            end = helperGhost,
                            obstacles = obstacles.filter { it !== finalCoin },
                            excluded = helperCoin,
                            clearanceRadius = board.coinRadiusPx * 1.85f
                        )
                    ) {
                        continue
                    }

                    val strikerGhost = helperCoin.center - dirHg * strikerImpactDist
                    if (!isInsidePlayableBoard(strikerGhost, board, margin = board.strikerRadiusPx)) continue

                    for (strikerPos in strikerCandidates) {
                        val sg = strikerGhost - strikerPos
                        if (sg.length() < board.strikerRadiusPx * 1.2f) continue
                        val dirSg = sg.normalized()
                        val entryCutDeg = (acos(dirSg.dot(dirHg).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                        if (entryCutDeg > 36f) continue

                        if (!isSegmentClear(
                                start = strikerPos,
                                end = strikerGhost,
                                obstacles = obstacles,
                                excluded = helperCoin,
                                clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                            )
                        ) {
                            continue
                        }
                        if (willStrikerScratchIntoPocket(strikerPos, strikerGhost, dirSg, dirHg, entryCutDeg, board, extraScratchGuard)) {
                            continue
                        }

                        val score = (0.61f - (comboCutDeg + entryCutDeg) / 140f).coerceIn(0.45f, 0.65f)
                        outCandidates?.add(
                            RolloutCandidate(
                                strikerPos = strikerPos,
                                baseDir = dirSg,
                                ghostBall = strikerGhost,
                                targetCoin = finalCoin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = entryCutDeg,
                                isBankShot = false,
                                isComboShot = true,
                                isBreakShot = false,
                                basePullMultiplier = 1.26f,
                                staticScore = score,
                                totalDistance = sg.length() + distHelper + distPocket
                            )
                        )
                        if (bestCombo == null || score > bestCombo.confidenceScore) {
                            val dragEnd = computeSlingshotDragEnd(
                                strikerPos = strikerPos,
                                shotDir = dirSg,
                                totalDistance = sg.length() + distHelper + distPocket,
                                boardWidth = board.width,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                cutAngleDeg = entryCutDeg + comboCutDeg * 0.5f,
                                powerMultiplier = 1.28f // Extra impulse for 2-stage collision transfer
                            )
                            bestCombo = CarromShotSolution(
                                strikerPos = strikerPos,
                                dragEnd = dragEnd,
                                ghostBall = strikerGhost,
                                targetCoin = finalCoin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = entryCutDeg,
                                isBankShot = false,
                                isComboShot = true,
                                powerStyle = CarromPowerStyle.HARD,
                                confidenceScore = score,
                                reason = "2-Coin Combo ${helperCoin.color.name}->${finalCoin.color.name} (${entryCutDeg.toInt()}°) -> ${CarromMemoryData.pocketName(pocketIdx)}"
                            )
                        }
                    }
                }
            }
        }
        return bestCombo
    }

    private fun solveClusterBreakShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData? = null,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        if (targetPieces.isEmpty() || strikerCandidates.isEmpty()) return null

        // Pick the target coin with the highest local neighbor density (cluster/break target)
        val anchorCoin = targetPieces.maxByOrNull { candidate ->
            obstacles.count { other -> (other.center - candidate.center).length() <= board.coinRadiusPx * 4.5f }
        } ?: return null

        var nearestPocketIdx = 0
        var nearestDist = Float.MAX_VALUE
        board.pockets.forEachIndexed { idx, p ->
            val d = (p - anchorCoin.center).length()
            if (d < nearestDist) {
                nearestDist = d
                nearestPocketIdx = idx
            }
        }
        val nearestPocket = board.pockets[nearestPocketIdx]
        var bestBreak: CarromShotSolution? = null
        var bestScore = Float.NEGATIVE_INFINITY
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        for (strikerPos in strikerCandidates) {
            val toCoin = anchorCoin.center - strikerPos
            if (toCoin.length() < board.strikerRadiusPx * 1.5f) continue
            val dir = toCoin.normalized()

            val clearPath = isSegmentClear(
                start = strikerPos,
                end = anchorCoin.center,
                obstacles = obstacles,
                excluded = anchorCoin,
                clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.85f
            )
            if (willStrikerScratchIntoPocket(strikerPos, anchorCoin.center, dir, dir, 0f, board, extraScratchGuard)) {
                continue
            }
            if (!CarromPhysicsSimulator.isBreakTrajectoryScratchFree(
                    board = board,
                    pieces = pieces,
                    strikerPos = strikerPos,
                    shotDir = dir,
                    pullMultiplier = 1.25f,
                    myCoinColor = myCoinColor,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
            ) {
                continue
            }

            // Prefer unobstructed hit into cluster with verified zero-scratch physics
            val score = (if (clearPath) 0.56f else 0.51f) - (toCoin.length() / (board.width * 4f))
            if (score > bestScore) {
                bestScore = score
                val dragEnd = computeSlingshotDragEnd(
                    strikerPos = strikerPos,
                    shotDir = dir,
                    totalDistance = board.width * 1.35f,
                    boardWidth = board.width,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    cutAngleDeg = 0f,
                    powerMultiplier = 1.25f
                )
                bestBreak = CarromShotSolution(
                    strikerPos = strikerPos,
                    dragEnd = dragEnd,
                    ghostBall = anchorCoin.center,
                    targetCoin = anchorCoin,
                    targetPocket = nearestPocket,
                    pocketIndex = nearestPocketIdx,
                    cutAngleDeg = 0f,
                    isBankShot = false,
                    isBreakShot = true,
                    powerStyle = CarromPowerStyle.HARD,
                    confidenceScore = score.coerceIn(0.50f, 0.57f),
                    reason = "Safe Break / Cluster Scatter -> ${anchorCoin.color.name} (${anchorCoin.center.x.toInt()},${anchorCoin.center.y.toInt()})"
                )
            }
        }

        // Safe zero-scratch fallback sweep if direct anchor direction failed scratch guard
        if (bestBreak == null) {
            val strikerPos = strikerCandidates.first()
            val candidateDirs = listOf(
                Point2D(0.18f, -0.98f).normalized(),
                Point2D(-0.18f, -0.98f).normalized(),
                Point2D(0.35f, -0.93f).normalized(),
                Point2D(-0.35f, -0.93f).normalized(),
                Point2D(0f, -1f)
            )
            val safeDir = candidateDirs.firstOrNull { candDir ->
                CarromPhysicsSimulator.isBreakTrajectoryScratchFree(
                    board = board,
                    pieces = pieces,
                    strikerPos = strikerPos,
                    shotDir = candDir,
                    pullMultiplier = 0.88f,
                    myCoinColor = myCoinColor,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
            } ?: Point2D(0f, -1f)

            val dragEnd = computeSlingshotDragEnd(
                strikerPos = strikerPos,
                shotDir = safeDir,
                totalDistance = board.width * 0.95f,
                boardWidth = board.width,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                cutAngleDeg = 0f,
                powerMultiplier = 0.88f
            )
            bestBreak = CarromShotSolution(
                strikerPos = strikerPos,
                dragEnd = dragEnd,
                ghostBall = strikerPos + safeDir * (board.width * 0.4f),
                targetCoin = anchorCoin,
                targetPocket = nearestPocket,
                pocketIndex = nearestPocketIdx,
                cutAngleDeg = 0f,
                isBankShot = false,
                isBreakShot = true,
                powerStyle = CarromPowerStyle.MEDIUM,
                confidenceScore = 0.51f,
                reason = "Zero-Foul Safe Break -> ${anchorCoin.color.name} (${anchorCoin.center.x.toInt()},${anchorCoin.center.y.toInt()})"
            )
        }
        return bestBreak
    }

    private fun buildStrikerCandidates(
        board: CarromBoardLayout,
        obstacles: List<CarromPiece>,
        existingStriker: CarromPiece?,
        canRepositionStriker: Boolean
    ): List<Point2D> {
        val margin = board.strikerRadiusPx * 2.1f
        val minX = board.left + margin
        val maxX = board.right - margin
        if (maxX <= minX) return emptyList()

        val candidates = mutableListOf<Point2D>()
        existingStriker?.let {
            val clampedX = it.center.x.coerceIn(minX, maxX)
            candidates.add(Point2D(clampedX, it.center.y))
        }

        // If we cannot reposition via a separate slider knob and already found the striker,
        // shoot ONLY from its actual position rather than dragging empty wood.
        if (existingStriker != null && !canRepositionStriker) {
            return candidates
        }

        val steps = 24
        val stepSize = (maxX - minX) / steps
        for (i in 0..steps) {
            val pos = Point2D(minX + i * stepSize, board.baselineY)
            if (isPositionFree(pos, obstacles, board.coinRadiusPx + board.strikerRadiusPx * 0.95f)) {
                candidates.add(pos)
            }
        }
        return candidates
    }

    private fun isPositionFree(
        point: Point2D,
        obstacles: List<CarromPiece>,
        minDistance: Float
    ): Boolean {
        return obstacles.none { obs -> (obs.center - point).length() < minDistance }
    }

    internal fun isSegmentClear(
        start: Point2D,
        end: Point2D,
        obstacles: List<CarromPiece>,
        excluded: CarromPiece,
        clearanceRadius: Float
    ): Boolean {
        val seg = end - start
        val segLenSq = seg.x * seg.x + seg.y * seg.y
        if (segLenSq < 1e-3f) return true

        for (obs in obstacles) {
            if (obs === excluded) continue
            val toObs = obs.center - start
            val t = (toObs.dot(seg) / segLenSq).coerceIn(0f, 1f)
            // Ignore exact endpoints near start/end
            if (t <= 0.03f || t >= 0.97f) continue
            val closest = Point2D(start.x + seg.x * t, start.y + seg.y * t)
            val dist = (obs.center - closest).length()
            if (dist < clearanceRadius) {
                return false
            }
        }
        return true
    }

    internal fun willStrikerScratchIntoPocket(
        strikerPos: Point2D,
        ghostBall: Point2D,
        dirSg: Point2D,
        dirCp: Point2D,
        cutAngleDeg: Float,
        board: CarromBoardLayout,
        extraSafetyMargin: Boolean = false
    ): Boolean {
        val followMult = if (extraSafetyMargin) 1.48f else 1.32f
        val deflectMult = if (extraSafetyMargin) 1.34f else 1.22f

        // 1. Nearly straight follow-through (< 11 deg): striker continues roughly along dirSg
        if (cutAngleDeg < 11f) {
            val followEnd = ghostBall + dirSg * (board.width * 0.95f)
            for (pocket in board.pockets) {
                if (distancePointToSegment(pocket, ghostBall, followEnd) < board.pocketRadiusPx * followMult) {
                    return true
                }
            }
        }

        // 2. Deflection tangent ray: post-collision striker moves perpendicular to dirCp
        // Tangent sign points in the same half-plane as dirSg
        val tangent1 = Point2D(-dirCp.y, dirCp.x)
        val tangent = if (tangent1.dot(dirSg) >= 0f) tangent1 else Point2D(dirCp.y, -dirCp.x)
        val deflectEnd = ghostBall + tangent * (board.width * 0.7f)
        for (pocket in board.pockets) {
            if (distancePointToSegment(pocket, ghostBall, deflectEnd) < board.pocketRadiusPx * deflectMult) {
                return true
            }
        }
        return false
    }

    private fun distancePointToSegment(p: Point2D, a: Point2D, b: Point2D): Float {
        val ab = b - a
        val abLenSq = ab.x * ab.x + ab.y * ab.y
        if (abLenSq < 1e-4f) return (p - a).length()
        val t = ((p - a).dot(ab) / abLenSq).coerceIn(0f, 1f)
        val proj = Point2D(a.x + ab.x * t, a.y + ab.y * t)
        return (p - proj).length()
    }

    private fun isInsidePlayableBoard(p: Point2D, board: CarromBoardLayout, margin: Float): Boolean {
        return p.x in (board.left + margin)..(board.right - margin) &&
            p.y in (board.top + margin)..(board.bottom - margin)
    }

    internal fun computeSlingshotDragEnd(
        strikerPos: Point2D,
        shotDir: Point2D,
        totalDistance: Float,
        boardWidth: Float,
        screenWidth: Int,
        screenHeight: Int,
        cutAngleDeg: Float = 0f,
        powerMultiplier: Float = 1.0f
    ): Point2D {
        // Momentum transfer scales with cos(cutAngle), so angled cuts need ~1/cos(theta) stronger pull
        val cutRad = (cutAngleDeg.coerceIn(0f, 68f) * PI / 180.0)
        val cutCompensation = (1.0 / kotlin.math.cos(cutRad)).toFloat().coerceIn(1.0f, 1.85f)
        val basePower = (totalDistance / boardWidth).coerceIn(0.45f, 1.35f)
        val effectivePower = (basePower * cutCompensation * powerMultiplier).coerceIn(0.45f, 1.75f)
        val minPull = max(150f, boardWidth * 0.16f)
        val maxPull = min(400f, boardWidth * 0.44f).coerceAtLeast(minPull + 20f)
        val pullLength = (boardWidth * 0.28f * effectivePower).coerceIn(minPull, maxPull)
        val rawEnd = strikerPos - shotDir * pullLength
        return Point2D(
            x = rawEnd.x.coerceIn(16f, max(17f, screenWidth - 16f)),
            y = rawEnd.y.coerceIn(16f, max(17f, screenHeight - 16f))
        )
    }
}
