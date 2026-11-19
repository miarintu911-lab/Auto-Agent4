package com.example.game

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.agent.AgentKnowledgeStore
import com.example.agent.TaskPlaybook
import java.io.File
import java.util.Locale

private const val CARROM_MEMORY_FILE = "tappilot_carrom_memory.json"
private const val CARROM_BUNDLE_FORMAT = "tappilot_carrom_training_bundle_v1"
private const val MAX_RECENT_LESSONS = 16
private const val MAX_FOUL_SIGNATURES = 250

enum class CarromAngleBucket(val label: String) {
    STRAIGHT("Straight (0-14°)"),
    MEDIUM_CUT("Medium Cut (15-34°)"),
    SHARP_CUT("Sharp Cut (35-65°)");

    companion object {
        fun fromAngle(cutAngleDeg: Float): CarromAngleBucket = when {
            cutAngleDeg < 15f -> STRAIGHT
            cutAngleDeg < 35f -> MEDIUM_CUT
            else -> SHARP_CUT
        }
    }
}

enum class CarromPowerStyle(val label: String) {
    SOFT("Soft / Gentle"),
    MEDIUM("Medium"),
    HARD("Hard / Power");

    companion object {
        fun fromPullMultiplier(effectiveMultiplier: Float): CarromPowerStyle = when {
            effectiveMultiplier < 0.88f -> SOFT
            effectiveMultiplier > 1.15f -> HARD
            else -> MEDIUM
        }
    }
}

enum class CarromShotOutcomeType {
    POCKETED_MY_COIN,
    POCKETED_QUEEN,
    FOUL_SCRATCH_OR_PENALTY,
    MISSED_OR_NO_POT
}

data class PocketShotStats(
    val key: String,
    val pocketIndex: Int,
    val angleBucket: CarromAngleBucket,
    val attempts: Int = 0,
    val successes: Int = 0,
    val fouls: Int = 0,
    val misses: Int = 0,
    val learnedPowerScale: Float = 1.0f
) {
    fun confidenceAdjustment(): Float {
        if (attempts == 0) return 0f
        val successRate = successes.toFloat() / attempts.toFloat()
        val foulRate = fouls.toFloat() / attempts.toFloat()
        return ((successRate - 0.35f) * 0.24f - foulRate * 0.32f).coerceIn(-0.24f, 0.20f)
    }
}

data class StrikerPlacementStats(
    val movedAttempts: Int = 0,
    val movedSuccesses: Int = 0,
    val movedFouls: Int = 0,
    val inPlaceAttempts: Int = 0,
    val inPlaceSuccesses: Int = 0,
    val inPlaceFouls: Int = 0
) {
    fun inPlaceBonusAdjustment(): Float {
        if (inPlaceAttempts < 2 && movedAttempts < 2) return 0f
        val inPlaceRate = if (inPlaceAttempts > 0) {
            (inPlaceSuccesses - inPlaceFouls * 1.3f) / inPlaceAttempts.toFloat()
        } else {
            0f
        }
        val movedRate = if (movedAttempts > 0) {
            (movedSuccesses - movedFouls * 1.3f) / movedAttempts.toFloat()
        } else {
            0f
        }
        return ((inPlaceRate - movedRate) * 0.14f).coerceIn(-0.10f, 0.14f)
    }
}

data class ScratchZoneHeatmap(
    val zoneRiskByKey: Map<String, Float> = emptyMap()
) {
    fun riskFor(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): Float {
        val zoneKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pocketKey = "P${pocketIndex.coerceIn(0, 3)}"
        val zoneRisk = zoneRiskByKey[zoneKey] ?: 0f
        val pocketBaseRisk = (zoneRiskByKey[pocketKey] ?: 0f) * 0.45f
        return (zoneRisk + pocketBaseRisk).coerceIn(0f, 0.85f)
    }

    fun recordFoul(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): ScratchZoneHeatmap {
        val next = zoneRiskByKey.toMutableMap()
        val zKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pKey = "P${pocketIndex.coerceIn(0, 3)}"
        next[zKey] = ((next[zKey] ?: 0f) + 0.28f).coerceIn(0f, 0.85f)
        next[pKey] = ((next[pKey] ?: 0f) + 0.14f).coerceIn(0f, 0.55f)
        return copy(zoneRiskByKey = next)
    }

    fun recordCleanSuccess(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): ScratchZoneHeatmap {
        val next = zoneRiskByKey.toMutableMap()
        val zKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pKey = "P${pocketIndex.coerceIn(0, 3)}"
        next[zKey]?.let { prev ->
            val decayed = (prev - 0.10f).coerceAtLeast(0f)
            if (decayed <= 0.01f) next.remove(zKey) else next[zKey] = decayed
        }
        next[pKey]?.let { prev ->
            val decayed = (prev - 0.05f).coerceAtLeast(0f)
            if (decayed <= 0.01f) next.remove(pKey) else next[pKey] = decayed
        }
        return copy(zoneRiskByKey = next)
    }

    companion object {
        fun zoneKey(pocketIndex: Int, cutAngleDeg: Float, strikerNormalizedX: Float): String {
            val sector = when {
                strikerNormalizedX < 0.36f -> "LEFT"
                strikerNormalizedX > 0.64f -> "RIGHT"
                else -> "MID"
            }
            val bucket = CarromAngleBucket.fromAngle(cutAngleDeg).name
            return "P${pocketIndex.coerceIn(0, 3)}_${sector}_$bucket"
        }
    }
}

data class MatchPhysicsCalibration(
    val frictionScale: Float = 1.0f,
    val pocketJawSafetyBuffer: Float = 0f,
    val sliderGain: Float = 1.0f,
    val shotPowerGain: Float = 1.0f,
    val sliderSamples: Int = 0,
    val shotsThisMatch: Int = 0
)

data class CarromFoulSignature(
    val signatureKey: String,
    val pocketIndex: Int,
    val strikerSector: String,
    val coinRowZone: Int,
    val coinColZone: Int,
    val angleBucket: CarromAngleBucket,
    val powerStyle: CarromPowerStyle,
    val foulCount: Int = 1,
    val cleanSuccessCount: Int = 0,
    val lastFoulMs: Long = 0L
) {
    fun isBlocked(): Boolean = foulCount >= 1 && foulCount > cleanSuccessCount
}

data class CarromMemoryData(
    val statsByBucket: Map<String, PocketShotStats> = emptyMap(),
    val placementStats: StrikerPlacementStats = StrikerPlacementStats(),
    val scratchHeatmap: ScratchZoneHeatmap = ScratchZoneHeatmap(),
    val matchCalibration: MatchPhysicsCalibration = MatchPhysicsCalibration(),
    val foulSignatures: Map<String, CarromFoulSignature> = emptyMap(),
    val straightFoulCount: Int = 0,
    val recentTactics: List<String> = emptyList(),
    val recentFouls: List<String> = emptyList(),
    val lastUpdatedMs: Long = 0L
) {
    fun blockedFoulCount(): Int = foulSignatures.values.count { it.isBlocked() }

    fun isFoulBlacklisted(
        board: CarromBoardLayout,
        coinCenter: Point2D,
        strikerPos: Point2D,
        pocketIndex: Int,
        cutAngleDeg: Float,
        powerStyle: CarromPowerStyle
    ): Boolean {
        val (rowZone, colZone) = coinZoneFor(coinCenter, board)
        val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
        return isFoulBlacklisted(
            pocketIndex = pocketIndex,
            strikerNormalizedX = normStrikerX,
            coinRowZone = rowZone,
            coinColZone = colZone,
            cutAngleDeg = cutAngleDeg,
            powerStyle = powerStyle
        )
    }

    fun isFoulBlacklisted(
        pocketIndex: Int,
        strikerNormalizedX: Float,
        coinRowZone: Int,
        coinColZone: Int,
        cutAngleDeg: Float,
        powerStyle: CarromPowerStyle
    ): Boolean {
        if (foulSignatures.isEmpty() || coinRowZone !in 0..3 || coinColZone !in 0..3) return false
        val sector = strikerSectorFor(strikerNormalizedX)
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val exactKey = foulSignatureKey(
            pocketIndex = pocketIndex,
            strikerSector = sector,
            coinRowZone = coinRowZone,
            coinColZone = coinColZone,
            angleBucket = bucket,
            powerStyle = powerStyle
        )
        if (foulSignatures[exactKey]?.isBlocked() == true) {
            return true
        }
        // If repeated spatial fouls (>= 2 net fouls) happen across powers at this exact board zone & cut bucket, block all powers.
        var spatialFouls = 0
        var spatialClean = 0
        for (pwr in CarromPowerStyle.entries) {
            val k = foulSignatureKey(pocketIndex, sector, coinRowZone, coinColZone, bucket, pwr)
            foulSignatures[k]?.let { sig ->
                spatialFouls += sig.foulCount
                spatialClean += sig.cleanSuccessCount
            }
        }
        return spatialFouls >= 2 && spatialFouls > spatialClean + 1
    }
    fun statsFor(pocketIndex: Int, cutAngleDeg: Float): PocketShotStats? {
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        return statsByBucket[bucketKey(pocketIndex, bucket)]
    }

    fun learnedPowerScaleFor(pocketIndex: Int, cutAngleDeg: Float): Float {
        return statsFor(pocketIndex, cutAngleDeg)?.learnedPowerScale ?: 1.0f
    }

    fun confidenceBiasFor(pocketIndex: Int, cutAngleDeg: Float): Float {
        return statsFor(pocketIndex, cutAngleDeg)?.confidenceAdjustment() ?: 0f
    }

    fun needsExtraScratchGuard(): Boolean = straightFoulCount >= 1

    fun formatForPrompt(): String {
        if (statsByBucket.isEmpty() && foulSignatures.isEmpty() && recentTactics.isEmpty() && recentFouls.isEmpty()) {
            return ""
        }
        val bestBuckets = statsByBucket.values
            .filter { it.successes > 0 }
            .sortedByDescending { it.successes - it.fouls }
            .take(4)
            .joinToString("\n") { st ->
                "- ${pocketName(st.pocketIndex)} | ${st.angleBucket.label}: ${st.successes}/${st.attempts} potted, powerScale=${String.format(Locale.US, "%.2f", st.learnedPowerScale)}"
            }
        val foulBuckets = statsByBucket.values
            .filter { it.fouls > 0 }
            .sortedByDescending { it.fouls }
            .take(4)
            .joinToString("\n") { st ->
                "- AVOID ${pocketName(st.pocketIndex)} | ${st.angleBucket.label}: ${st.fouls} foul(s)"
            }
        val blockedSigsText = foulSignatures.values
            .filter { it.isBlocked() }
            .sortedByDescending { it.foulCount }
            .take(5)
            .joinToString("\n") { sig ->
                "- BLOCKED: ${pocketName(sig.pocketIndex)} | Striker=${sig.strikerSector} | Zone=(r${sig.coinRowZone},c${sig.coinColZone}) | ${sig.angleBucket.label} | ${sig.powerStyle.label} (${sig.foulCount} foul(s))"
            }
        val placementNote = buildString {
            val ps = placementStats
            if (ps.movedAttempts + ps.inPlaceAttempts > 0) {
                append(
                    "Striker Placement History: Slide-Move ${ps.movedSuccesses}/${ps.movedAttempts} (${ps.movedFouls} fouls) vs Shoot-In-Place ${ps.inPlaceSuccesses}/${ps.inPlaceAttempts} (${ps.inPlaceFouls} fouls)."
                )
            }
        }
        val tacticsText = recentTactics.takeLast(5).joinToString("\n") { "+ $it" }
        val foulsText = recentFouls.takeLast(5).joinToString("\n") { "! $it" }

        return listOfNotNull(
            "CARROM ADAPTIVE SHOT MEMORY (LEARNED FROM YOUR LIVE MATCHES):",
            bestBuckets.takeIf { it.isNotBlank() }?.let { "Best Pockets & Angles:\n$it" },
            foulBuckets.takeIf { it.isNotBlank() }?.let { "Known Foul Patterns:\n$it" },
            blockedSigsText.takeIf { it.isNotBlank() }?.let { "HARD-BLOCKED FOUL SIGNATURES (NEVER SHOOT THESE):\n$it" },
            placementNote.takeIf { it.isNotBlank() },
            tacticsText.takeIf { it.isNotBlank() }?.let { "Recent Winning Adjustments:\n$it" },
            foulsText.takeIf { it.isNotBlank() }?.let { "Recent Foul/Power Warnings:\n$it" }
        ).joinToString("\n")
    }

    companion object {
        fun bucketKey(pocketIndex: Int, bucket: CarromAngleBucket): String {
            return "P${pocketIndex.coerceIn(0, 3)}_${bucket.name}"
        }

        fun strikerSectorFor(strikerNormalizedX: Float): String = when {
            strikerNormalizedX < 0.36f -> "LEFT"
            strikerNormalizedX > 0.64f -> "RIGHT"
            else -> "MID"
        }

        fun coinZoneFor(point: Point2D, board: CarromBoardLayout): Pair<Int, Int> {
            val normX = ((point.x - board.left) / board.width).coerceIn(0f, 0.999f)
            val normY = ((point.y - board.top) / board.height).coerceIn(0f, 0.999f)
            val row = (normY * 4f).toInt().coerceIn(0, 3)
            val col = (normX * 4f).toInt().coerceIn(0, 3)
            return row to col
        }

        fun foulSignatureKey(
            pocketIndex: Int,
            strikerSector: String,
            coinRowZone: Int,
            coinColZone: Int,
            angleBucket: CarromAngleBucket,
            powerStyle: CarromPowerStyle
        ): String {
            return "P${pocketIndex.coerceIn(0, 3)}_${strikerSector}_R${coinRowZone.coerceIn(0, 3)}C${coinColZone.coerceIn(0, 3)}_${angleBucket.name}_${powerStyle.name}"
        }

        fun pocketName(pocketIndex: Int): String = when (pocketIndex) {
            0 -> "Top-Left Pocket"
            1 -> "Top-Right Pocket"
            2 -> "Bottom-Left Pocket"
            3 -> "Bottom-Right Pocket"
            else -> "Pocket #$pocketIndex"
        }
    }
}

data class BoardPieceCounts(
    val myCount: Int,
    val opponentCount: Int,
    val queenCount: Int
)

data class PendingCarromShotRecord(
    val myCoinColor: CoinColor,
    val targetCoinColor: CoinColor,
    val pocketIndex: Int,
    val cutAngleDeg: Float,
    val strikerNormalizedX: Float = 0.5f,
    val coinRowZone: Int = -1,
    val coinColZone: Int = -1,
    val powerStyle: CarromPowerStyle,
    val movedStrikerWithSlider: Boolean,
    val isBankShot: Boolean,
    val isComboShot: Boolean,
    val isBreakShot: Boolean,
    val preCounts: BoardPieceCounts,
    val isFreestyle: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CarromEvaluationResult(
    val outcomeType: CarromShotOutcomeType,
    val updatedMemory: CarromMemoryData,
    val learnedTacticLesson: String?,
    val learnedFoulLesson: String?,
    val summaryMessage: String
)

class CarromShotMemoryStore(context: Context) {

    private val storageFile = File(context.filesDir, CARROM_MEMORY_FILE)
    private val mutex = Mutex()

    private val _memory = MutableStateFlow(CarromMemoryData())
    val memory: StateFlow<CarromMemoryData> = _memory.asStateFlow()

    suspend fun loadInitial() {
        mutex.withLock {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!storageFile.exists()) CarromMemoryData()
                    else parseJson(storageFile.readText())
                }.getOrDefault(CarromMemoryData())
            }
            _memory.value = loaded
        }
    }

    suspend fun recordShotOutcome(
        pendingShot: PendingCarromShotRecord,
        postCounts: BoardPieceCounts
    ): CarromEvaluationResult {
        return mutex.withLock {
            val evaluated = evaluateShotOutcome(
                current = _memory.value,
                pendingShot = pendingShot,
                postCounts = postCounts
            )
            persistLocked(evaluated.updatedMemory)
            evaluated
        }
    }

    suspend fun recordHumanTrainingShot(
        pendingShot: PendingCarromShotRecord,
        postCounts: BoardPieceCounts,
        observedPowerHint: Float? = null
    ): CarromEvaluationResult {
        return mutex.withLock {
            val evaluated = evaluateShotOutcome(
                current = _memory.value,
                pendingShot = pendingShot,
                postCounts = postCounts,
                lessonPrefix = "[Human Coach] "
            )
            val blendedMemory = if (
                observedPowerHint != null &&
                (evaluated.outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                    evaluated.outcomeType == CarromShotOutcomeType.POCKETED_QUEEN)
            ) {
                val cal = evaluated.updatedMemory.matchCalibration
                val clampedHint = observedPowerHint.coerceIn(0.80f, 1.25f)
                val newGain = ((cal.shotPowerGain * 0.85f) + (clampedHint * 0.15f)).coerceIn(0.78f, 1.28f)
                evaluated.updatedMemory.copy(
                    matchCalibration = cal.copy(shotPowerGain = newGain)
                )
            } else {
                evaluated.updatedMemory
            }
            persistLocked(blendedMemory)
            evaluated.copy(updatedMemory = blendedMemory)
        }
    }

    suspend fun updateSliderGain(observedRatio: Float) {
        val clamped = observedRatio.coerceIn(0.65f, 1.45f)
        mutex.withLock {
            val cur = _memory.value
            val prevCal = cur.matchCalibration
            val n = prevCal.sliderSamples
            val blended = ((prevCal.sliderGain * n.coerceAtMost(4)) + clamped) / (n.coerceAtMost(4) + 1)
            val updated = cur.copy(
                matchCalibration = prevCal.copy(
                    sliderGain = blended.coerceIn(0.65f, 1.45f),
                    sliderSamples = n + 1
                ),
                lastUpdatedMs = System.currentTimeMillis()
            )
            persistLocked(updated)
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            persistLocked(CarromMemoryData())
        }
    }

    suspend fun replaceWithImported(memoryData: CarromMemoryData) {
        mutex.withLock {
            persistLocked(memoryData)
        }
    }

    private suspend fun persistLocked(data: CarromMemoryData) {
        _memory.value = data
        withContext(Dispatchers.IO) {
            runCatching {
                storageFile.writeText(serializeJson(data))
            }
        }
    }

    companion object {
        fun countBoardPieces(pieces: List<CarromPiece>, myCoinColor: CoinColor): BoardPieceCounts {
            val opponentColor = if (myCoinColor == CoinColor.WHITE) CoinColor.BLACK else CoinColor.WHITE
            return BoardPieceCounts(
                myCount = pieces.count { it.color == myCoinColor },
                opponentCount = pieces.count { it.color == opponentColor },
                queenCount = pieces.count { it.color == CoinColor.QUEEN }
            )
        }

        fun evaluateShotOutcome(
            current: CarromMemoryData,
            pendingShot: PendingCarromShotRecord,
            postCounts: BoardPieceCounts,
            lessonPrefix: String = ""
        ): CarromEvaluationResult {
            val pre = pendingShot.preCounts
            val myDelta = postCounts.myCount - pre.myCount
            val oppDelta = postCounts.opponentCount - pre.opponentCount
            val queenPocketed = pre.queenCount > 0 && postCounts.queenCount == 0
            val totalDelta = (postCounts.myCount + postCounts.opponentCount + postCounts.queenCount) -
                (pre.myCount + pre.opponentCount + pre.queenCount)

            val outcomeType = if (pendingShot.isFreestyle) {
                when {
                    // Freestyle: ONLY striker scratch (foul returning a penalty coin) is a foul; White, Black, and Red/Queen are all legal!
                    totalDelta > 0 -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    queenPocketed -> CarromShotOutcomeType.POCKETED_QUEEN
                    myDelta < 0 || oppDelta < 0 -> CarromShotOutcomeType.POCKETED_MY_COIN
                    else -> CarromShotOutcomeType.MISSED_OR_NO_POT
                }
            } else {
                when {
                    // My coin count increased -> striker pocketed or foul returned a coin!
                    myDelta > 0 -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    // Only opponent coin went in while none of mine did -> foul/wrong pot
                    oppDelta < 0 && myDelta >= 0 && !queenPocketed -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    // Queen went in cleanly without penalty
                    queenPocketed -> CarromShotOutcomeType.POCKETED_QUEEN
                    // My coin decreased -> cleanly pocketed!
                    myDelta < 0 -> CarromShotOutcomeType.POCKETED_MY_COIN
                    else -> CarromShotOutcomeType.MISSED_OR_NO_POT
                }
            }

            val pocketIdx = pendingShot.pocketIndex.coerceIn(0, 3)
            val pocketLabel = CarromMemoryData.pocketName(pocketIdx)
            val angleBucket = CarromAngleBucket.fromAngle(pendingShot.cutAngleDeg)
            val key = CarromMemoryData.bucketKey(pocketIdx, angleBucket)
            val existingStats = current.statsByBucket[key] ?: PocketShotStats(
                key = key,
                pocketIndex = pocketIdx,
                angleBucket = angleBucket
            )

            val moveLabel = if (pendingShot.movedStrikerWithSlider) "Slider Reposition" else "In-Place Shot"
            val powerLabel = pendingShot.powerStyle.label
            val shotKind = when {
                pendingShot.isBreakShot -> "Cluster Break"
                pendingShot.isComboShot -> "2-Coin Combo"
                pendingShot.isBankShot -> "1-Rail Bank"
                else -> "${angleBucket.label} Cut"
            }

            var newPowerScale = existingStats.learnedPowerScale
            var tacticLesson: String? = null
            var foulLesson: String? = null
            var straightFouls = current.straightFoulCount

            val ps = current.placementStats
            val newPlacementStats = when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                    val potNoun = when {
                        outcomeType == CarromShotOutcomeType.POCKETED_QUEEN -> "Queen"
                        pendingShot.isFreestyle -> "${pendingShot.targetCoinColor.name} (Freestyle)"
                        else -> pendingShot.myCoinColor.name
                    }
                    tacticLesson = "${lessonPrefix}$pocketLabel ($shotKind, $powerLabel, $moveLabel) pocketed $potNoun cleanly!"
                    // Slightly reinforce working power scale toward current effective scale
                    if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(
                            movedAttempts = ps.movedAttempts + 1,
                            movedSuccesses = ps.movedSuccesses + 1
                        )
                    } else {
                        ps.copy(
                            inPlaceAttempts = ps.inPlaceAttempts + 1,
                            inPlaceSuccesses = ps.inPlaceSuccesses + 1
                        )
                    }
                }
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                    // Too hard or straight follow-through scratched striker or hit foul -> soften future power!
                    newPowerScale = (existingStats.learnedPowerScale - 0.08f).coerceIn(0.72f, 1.38f)
                    if (angleBucket == CarromAngleBucket.STRAIGHT) {
                        straightFouls += 1
                    }
                    foulLesson = if (pendingShot.isFreestyle) {
                        "${lessonPrefix}FOUL (Striker Scratch) on $pocketLabel ($shotKind, $powerLabel, $moveLabel): soften pull & verify striker scratch path."
                    } else {
                        "${lessonPrefix}FOUL on $pocketLabel ($shotKind, $powerLabel, $moveLabel): soften pull & verify striker scratch path."
                    }
                    if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(
                            movedAttempts = ps.movedAttempts + 1,
                            movedFouls = ps.movedFouls + 1
                        )
                    } else {
                        ps.copy(
                            inPlaceAttempts = ps.inPlaceAttempts + 1,
                            inPlaceFouls = ps.inPlaceFouls + 1
                        )
                    }
                }
                CarromShotOutcomeType.MISSED_OR_NO_POT -> {
                    // Adaptive speed calibration: sharp cuts or bank shots missing usually need slightly more drive;
                    // straight/soft-distance hangers missing with HARD power bounce out, so soften slightly.
                    newPowerScale = when {
                        pendingShot.powerStyle == CarromPowerStyle.HARD && angleBucket == CarromAngleBucket.STRAIGHT ->
                            (existingStats.learnedPowerScale - 0.05f).coerceIn(0.72f, 1.38f)
                        angleBucket == CarromAngleBucket.SHARP_CUT || pendingShot.isBankShot ->
                            (existingStats.learnedPowerScale + 0.06f).coerceIn(0.72f, 1.38f)
                        else ->
                            (existingStats.learnedPowerScale + 0.03f).coerceIn(0.72f, 1.38f)
                    }
                    if (existingStats.misses >= 1 && (existingStats.misses + 1) % 2 == 0) {
                        val advice = if (newPowerScale > existingStats.learnedPowerScale) {
                            "increase drive power"
                        } else {
                            "shoot softer so coin doesn't bounce out"
                        }
                        foulLesson = "${lessonPrefix}Missed $pocketLabel ($shotKind, $powerLabel) — auto-adjusting to $advice."
                    }
                    if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(movedAttempts = ps.movedAttempts + 1)
                    } else {
                        ps.copy(inPlaceAttempts = ps.inPlaceAttempts + 1)
                    }
                }
            }

            val updatedBucket = existingStats.copy(
                attempts = existingStats.attempts + 1,
                successes = existingStats.successes + if (
                    outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                    outcomeType == CarromShotOutcomeType.POCKETED_QUEEN
                ) 1 else 0,
                fouls = existingStats.fouls + if (outcomeType == CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY) 1 else 0,
                misses = existingStats.misses + if (outcomeType == CarromShotOutcomeType.MISSED_OR_NO_POT) 1 else 0,
                learnedPowerScale = newPowerScale
            )

            val updatedMap = current.statsByBucket.toMutableMap()
            updatedMap[key] = updatedBucket

            val newTactics = if (tacticLesson != null) {
                (current.recentTactics.filterNot { it.equals(tacticLesson, true) } + tacticLesson)
                    .takeLast(MAX_RECENT_LESSONS)
            } else {
                current.recentTactics
            }
            val newFouls = if (foulLesson != null) {
                (current.recentFouls.filterNot { it.equals(foulLesson, true) } + foulLesson)
                    .takeLast(MAX_RECENT_LESSONS)
            } else {
                current.recentFouls
            }

            val prevCal = current.matchCalibration
            var newHeatmap = current.scratchHeatmap
            var newFrictionScale = prevCal.frictionScale
            var newJawBuffer = prevCal.pocketJawSafetyBuffer
            var newShotPowerGain = prevCal.shotPowerGain

            when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                    newHeatmap = newHeatmap.recordCleanSuccess(
                        pocketIdx,
                        pendingShot.cutAngleDeg,
                        pendingShot.strikerNormalizedX
                    )
                    newJawBuffer = (newJawBuffer - 0.8f).coerceIn(0f, 10f)
                    newShotPowerGain = ((newShotPowerGain * 0.94f) + 0.06f).coerceIn(0.78f, 1.28f)
                }
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                    newHeatmap = newHeatmap.recordFoul(
                        pocketIdx,
                        pendingShot.cutAngleDeg,
                        pendingShot.strikerNormalizedX
                    )
                    newJawBuffer = (newJawBuffer + 2.2f).coerceIn(0f, 10f)
                    newShotPowerGain = (newShotPowerGain - 0.04f).coerceIn(0.78f, 1.28f)
                }
                CarromShotOutcomeType.MISSED_OR_NO_POT -> {
                    if (pendingShot.isBankShot || angleBucket == CarromAngleBucket.SHARP_CUT) {
                        newFrictionScale = (newFrictionScale + 0.04f).coerceIn(0.80f, 1.28f)
                        newJawBuffer = (newJawBuffer + 1.4f).coerceIn(0f, 10f)
                        newShotPowerGain = (newShotPowerGain + 0.03f).coerceIn(0.78f, 1.28f)
                    } else if (pendingShot.powerStyle == CarromPowerStyle.HARD) {
                        newFrictionScale = (newFrictionScale - 0.03f).coerceIn(0.80f, 1.28f)
                        newShotPowerGain = (newShotPowerGain - 0.03f).coerceIn(0.78f, 1.28f)
                    } else {
                        newShotPowerGain = (newShotPowerGain + 0.02f).coerceIn(0.78f, 1.28f)
                    }
                }
            }

            val updatedFoulSignatures = current.foulSignatures.toMutableMap()
            if (!pendingShot.isBreakShot && pendingShot.coinRowZone in 0..3 && pendingShot.coinColZone in 0..3) {
                val sector = CarromMemoryData.strikerSectorFor(pendingShot.strikerNormalizedX)
                val sigKey = CarromMemoryData.foulSignatureKey(
                    pocketIndex = pocketIdx,
                    strikerSector = sector,
                    coinRowZone = pendingShot.coinRowZone,
                    coinColZone = pendingShot.coinColZone,
                    angleBucket = angleBucket,
                    powerStyle = pendingShot.powerStyle
                )
                val existingSig = updatedFoulSignatures[sigKey]
                when (outcomeType) {
                    CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                        val nextSig = if (existingSig != null) {
                            existingSig.copy(
                                foulCount = existingSig.foulCount + 1,
                                lastFoulMs = pendingShot.timestampMs
                            )
                        } else {
                            CarromFoulSignature(
                                signatureKey = sigKey,
                                pocketIndex = pocketIdx,
                                strikerSector = sector,
                                coinRowZone = pendingShot.coinRowZone,
                                coinColZone = pendingShot.coinColZone,
                                angleBucket = angleBucket,
                                powerStyle = pendingShot.powerStyle,
                                foulCount = 1,
                                cleanSuccessCount = 0,
                                lastFoulMs = pendingShot.timestampMs
                            )
                        }
                        updatedFoulSignatures[sigKey] = nextSig
                        if (updatedFoulSignatures.size > MAX_FOUL_SIGNATURES) {
                            val trimmed = updatedFoulSignatures.values
                                .sortedWith(
                                    compareByDescending<CarromFoulSignature> { it.foulCount - it.cleanSuccessCount }
                                        .thenByDescending { it.lastFoulMs }
                                )
                                .take(MAX_FOUL_SIGNATURES)
                            updatedFoulSignatures.clear()
                            trimmed.forEach { updatedFoulSignatures[it.signatureKey] = it }
                        }
                    }
                    CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                        if (existingSig != null) {
                            val nextClean = existingSig.cleanSuccessCount + 1
                            if (nextClean >= existingSig.foulCount + 2) {
                                updatedFoulSignatures.remove(sigKey)
                            } else {
                                updatedFoulSignatures[sigKey] = existingSig.copy(cleanSuccessCount = nextClean)
                            }
                        }
                    }
                    CarromShotOutcomeType.MISSED_OR_NO_POT -> Unit
                }
            }

            val updatedMemory = current.copy(
                statsByBucket = updatedMap,
                placementStats = newPlacementStats,
                scratchHeatmap = newHeatmap,
                matchCalibration = prevCal.copy(
                    frictionScale = newFrictionScale,
                    pocketJawSafetyBuffer = newJawBuffer,
                    shotPowerGain = newShotPowerGain,
                    shotsThisMatch = prevCal.shotsThisMatch + 1
                ),
                foulSignatures = updatedFoulSignatures,
                straightFoulCount = straightFouls,
                recentTactics = newTactics,
                recentFouls = newFouls,
                lastUpdatedMs = System.currentTimeMillis()
            )

            val summary = when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN ->
                    "Carrom Outcome: POCKETED coin in $pocketLabel ($powerLabel, $moveLabel) — saved to memory!"
                CarromShotOutcomeType.POCKETED_QUEEN ->
                    "Carrom Outcome: POCKETED RED QUEEN in $pocketLabel ($powerLabel) — remember to cover Queen!"
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY ->
                    "Carrom Outcome: FOUL detected (penalty coin/opponent pot) on $pocketLabel — power softened & guard saved!"
                CarromShotOutcomeType.MISSED_OR_NO_POT ->
                    "Carrom Outcome: No coin potted on $pocketLabel ($powerLabel) — powerScale calibrated to ${String.format(Locale.US, "%.2f", newPowerScale)}."
            }

            return CarromEvaluationResult(
                outcomeType = outcomeType,
                updatedMemory = updatedMemory,
                learnedTacticLesson = tacticLesson,
                learnedFoulLesson = foulLesson,
                summaryMessage = summary
            )
        }

        fun serializeJson(data: CarromMemoryData): String {
            val root = JSONObject()
            val bucketsArray = JSONArray()
            for (st in data.statsByBucket.values) {
                bucketsArray.put(
                    JSONObject()
                        .put("key", st.key)
                        .put("pocketIndex", st.pocketIndex)
                        .put("angleBucket", st.angleBucket.name)
                        .put("attempts", st.attempts)
                        .put("successes", st.successes)
                        .put("fouls", st.fouls)
                        .put("misses", st.misses)
                        .put("learnedPowerScale", st.learnedPowerScale.toDouble())
                )
            }
            val ps = data.placementStats
            val placementObj = JSONObject()
                .put("movedAttempts", ps.movedAttempts)
                .put("movedSuccesses", ps.movedSuccesses)
                .put("movedFouls", ps.movedFouls)
                .put("inPlaceAttempts", ps.inPlaceAttempts)
                .put("inPlaceSuccesses", ps.inPlaceSuccesses)
                .put("inPlaceFouls", ps.inPlaceFouls)

            val heatmapObj = JSONObject()
            for ((zKey, risk) in data.scratchHeatmap.zoneRiskByKey) {
                heatmapObj.put(zKey, risk.toDouble())
            }
            val cal = data.matchCalibration
            val calObj = JSONObject()
                .put("frictionScale", cal.frictionScale.toDouble())
                .put("pocketJawSafetyBuffer", cal.pocketJawSafetyBuffer.toDouble())
                .put("sliderGain", cal.sliderGain.toDouble())
                .put("shotPowerGain", cal.shotPowerGain.toDouble())
                .put("sliderSamples", cal.sliderSamples)
                .put("shotsThisMatch", cal.shotsThisMatch)

            val foulSigsArray = JSONArray()
            for (sig in data.foulSignatures.values) {
                foulSigsArray.put(
                    JSONObject()
                        .put("signatureKey", sig.signatureKey)
                        .put("pocketIndex", sig.pocketIndex)
                        .put("strikerSector", sig.strikerSector)
                        .put("coinRowZone", sig.coinRowZone)
                        .put("coinColZone", sig.coinColZone)
                        .put("angleBucket", sig.angleBucket.name)
                        .put("powerStyle", sig.powerStyle.name)
                        .put("foulCount", sig.foulCount)
                        .put("cleanSuccessCount", sig.cleanSuccessCount)
                        .put("lastFoulMs", sig.lastFoulMs)
                )
            }

            root.put("buckets", bucketsArray)
            root.put("placementStats", placementObj)
            root.put("scratchHeatmap", heatmapObj)
            root.put("matchCalibration", calObj)
            root.put("foulSignatures", foulSigsArray)
            root.put("straightFoulCount", data.straightFoulCount)
            root.put("recentTactics", JSONArray(data.recentTactics))
            root.put("recentFouls", JSONArray(data.recentFouls))
            root.put("lastUpdatedMs", data.lastUpdatedMs)
            return root.toString(2)
        }

        fun parseJson(raw: String): CarromMemoryData {
            if (raw.isBlank()) return CarromMemoryData()
            val root = JSONObject(raw)
            val bucketsArray = root.optJSONArray("buckets") ?: JSONArray()
            val map = LinkedHashMap<String, PocketShotStats>()
            for (i in 0 until bucketsArray.length()) {
                val obj = bucketsArray.optJSONObject(i) ?: continue
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
                val pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3)
                val angleBucket = runCatching {
                    CarromAngleBucket.valueOf(obj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                map[key] = PocketShotStats(
                    key = key,
                    pocketIndex = pocketIndex,
                    angleBucket = angleBucket,
                    attempts = obj.optInt("attempts", 0).coerceAtLeast(0),
                    successes = obj.optInt("successes", 0).coerceAtLeast(0),
                    fouls = obj.optInt("fouls", 0).coerceAtLeast(0),
                    misses = obj.optInt("misses", 0).coerceAtLeast(0),
                    learnedPowerScale = obj.optDouble("learnedPowerScale", 1.0).toFloat().coerceIn(0.72f, 1.38f)
                )
            }

            val pObj = root.optJSONObject("placementStats")
            val placementStats = if (pObj != null) {
                StrikerPlacementStats(
                    movedAttempts = pObj.optInt("movedAttempts", 0).coerceAtLeast(0),
                    movedSuccesses = pObj.optInt("movedSuccesses", 0).coerceAtLeast(0),
                    movedFouls = pObj.optInt("movedFouls", 0).coerceAtLeast(0),
                    inPlaceAttempts = pObj.optInt("inPlaceAttempts", 0).coerceAtLeast(0),
                    inPlaceSuccesses = pObj.optInt("inPlaceSuccesses", 0).coerceAtLeast(0),
                    inPlaceFouls = pObj.optInt("inPlaceFouls", 0).coerceAtLeast(0)
                )
            } else {
                StrikerPlacementStats()
            }

            fun jsonList(key: String): List<String> {
                val arr = root.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { idx ->
                    arr.optString(idx).trim().takeIf { it.isNotBlank() }
                }
            }

            val heatmapObj = root.optJSONObject("scratchHeatmap")
            val heatmapMap = LinkedHashMap<String, Float>()
            if (heatmapObj != null) {
                val keysIter = heatmapObj.keys()
                while (keysIter.hasNext()) {
                    val k = keysIter.next()
                    heatmapMap[k] = heatmapObj.optDouble(k, 0.0).toFloat().coerceIn(0f, 0.85f)
                }
            }

            val calObj = root.optJSONObject("matchCalibration")
            val calibration = if (calObj != null) {
                MatchPhysicsCalibration(
                    frictionScale = calObj.optDouble("frictionScale", 1.0).toFloat().coerceIn(0.80f, 1.28f),
                    pocketJawSafetyBuffer = calObj.optDouble("pocketJawSafetyBuffer", 0.0).toFloat().coerceIn(0f, 10f),
                    sliderGain = calObj.optDouble("sliderGain", 1.0).toFloat().coerceIn(0.65f, 1.45f),
                    shotPowerGain = calObj.optDouble("shotPowerGain", 1.0).toFloat().coerceIn(0.78f, 1.28f),
                    sliderSamples = calObj.optInt("sliderSamples", 0).coerceAtLeast(0),
                    shotsThisMatch = calObj.optInt("shotsThisMatch", 0).coerceAtLeast(0)
                )
            } else {
                MatchPhysicsCalibration()
            }

            val foulSigsArr = root.optJSONArray("foulSignatures") ?: JSONArray()
            val foulSigsMap = LinkedHashMap<String, CarromFoulSignature>()
            for (i in 0 until foulSigsArr.length()) {
                val obj = foulSigsArr.optJSONObject(i) ?: continue
                val key = obj.optString("signatureKey").takeIf { it.isNotBlank() } ?: continue
                val angleBucket = runCatching {
                    CarromAngleBucket.valueOf(obj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                val powerStyle = runCatching {
                    CarromPowerStyle.valueOf(obj.optString("powerStyle", CarromPowerStyle.MEDIUM.name))
                }.getOrDefault(CarromPowerStyle.MEDIUM)
                foulSigsMap[key] = CarromFoulSignature(
                    signatureKey = key,
                    pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3),
                    strikerSector = obj.optString("strikerSector", "MID").ifBlank { "MID" },
                    coinRowZone = obj.optInt("coinRowZone", 0).coerceIn(0, 3),
                    coinColZone = obj.optInt("coinColZone", 0).coerceIn(0, 3),
                    angleBucket = angleBucket,
                    powerStyle = powerStyle,
                    foulCount = obj.optInt("foulCount", 1).coerceAtLeast(0),
                    cleanSuccessCount = obj.optInt("cleanSuccessCount", 0).coerceAtLeast(0),
                    lastFoulMs = obj.optLong("lastFoulMs", 0L)
                )
            }

            return CarromMemoryData(
                statsByBucket = map,
                placementStats = placementStats,
                scratchHeatmap = ScratchZoneHeatmap(heatmapMap),
                matchCalibration = calibration,
                foulSignatures = foulSigsMap,
                straightFoulCount = root.optInt("straightFoulCount", 0).coerceAtLeast(0),
                recentTactics = jsonList("recentTactics"),
                recentFouls = jsonList("recentFouls"),
                lastUpdatedMs = root.optLong("lastUpdatedMs", 0L)
            )
        }

        fun exportTrainingBundleJson(
            memory: CarromMemoryData,
            carromPlaybook: TaskPlaybook?,
            exportedAtMs: Long = System.currentTimeMillis()
        ): String {
            val totalAttempts = maxOf(
                memory.statsByBucket.values.sumOf { it.attempts },
                memory.matchCalibration.shotsThisMatch
            )
            val totalPots = memory.statsByBucket.values.sumOf { it.successes }
            val blockedFouls = memory.blockedFoulCount()

            val root = JSONObject()
                .put("format", CARROM_BUNDLE_FORMAT)
                .put("exportedAtMs", exportedAtMs)
                .put(
                    "summary",
                    JSONObject()
                        .put("totalAttempts", totalAttempts)
                        .put("totalPots", totalPots)
                        .put("blockedFoulSignatures", blockedFouls)
                )
                .put("carromMemory", JSONObject(serializeJson(memory)))

            if (carromPlaybook != null) {
                val pbArr = JSONArray(AgentKnowledgeStore.serializePlaybooksJson(listOf(carromPlaybook)))
                if (pbArr.length() > 0) {
                    root.put("carromPlaybook", pbArr.getJSONObject(0))
                }
            }
            return root.toString(2)
        }

        fun parseTrainingBundleJson(raw: String): Pair<CarromMemoryData, TaskPlaybook?> {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) throw IllegalArgumentException("Empty Carrom JSON file.")
            val root = JSONObject(trimmed)
            return if (root.has("carromMemory")) {
                val memObj = root.getJSONObject("carromMemory")
                val mem = parseJson(memObj.toString())
                val pbObj = root.optJSONObject("carromPlaybook")
                val pb = if (pbObj != null) {
                    AgentKnowledgeStore.parsePlaybooksJson(JSONArray().put(pbObj).toString()).firstOrNull()
                } else {
                    null
                }
                mem to pb
            } else if (root.has("buckets") || root.has("foulSignatures") || root.has("matchCalibration")) {
                parseJson(trimmed) to null
            } else {
                throw IllegalArgumentException("File is not a valid TapPilot Carrom training JSON.")
            }
        }
    }
}
