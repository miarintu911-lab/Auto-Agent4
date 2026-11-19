package com.example.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val PREFS_ACTION_CACHE = "tappilot_action_cache"
private const val KEY_CACHE_JSON = "cached_actions_json"
private const val MAX_CACHED_ACTIONS = 80
private const val MIN_INTERACTIVE_NODES_FOR_CACHE = 4

data class PendingCacheCandidate(
    val goal: String,
    val packageName: String,
    val screenSignature: String,
    val interactiveNodeCount: Int,
    val action: PlannedAction,
    val targetNode: ScreenNode?,
    val hadConfirmedSafety: Boolean
)

data class CacheReplayMatch(
    val entry: CachedActionEntry,
    val action: PlannedAction,
    val resolvedSummary: String
)

class ActionCacheStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_ACTION_CACHE, Context.MODE_PRIVATE)

    private val _cachedActions = MutableStateFlow<List<CachedActionEntry>>(emptyList())
    val cachedActions: StateFlow<List<CachedActionEntry>> = _cachedActions.asStateFlow()

    suspend fun loadInitial() {
        val loaded = withContext(Dispatchers.IO) {
            parseJson(prefs.getString(KEY_CACHE_JSON, null))
        }
        _cachedActions.value = loaded
    }

    fun findReplayMatch(
        goal: String,
        snapshot: UiSnapshot
    ): CacheReplayMatch? {
        return matchEntryFromList(_cachedActions.value, goal, snapshot)
    }

    suspend fun recordVerifiedSuccess(
        candidate: PendingCacheCandidate,
        afterSnapshot: UiSnapshot
    ): CachedActionEntry? {
        if (!isEligibleForCache(candidate, afterSnapshot)) return null

        val action = candidate.action
        val node = candidate.targetNode
        val targetLabel = node?.displayLabel()?.take(60).orEmpty().ifBlank {
            when (action.actionType) {
                AgentActionType.SCROLL_DOWN -> "Scroll Down"
                AgentActionType.SCROLL_UP -> "Scroll Up"
                else -> ""
            }
        }
        if (targetLabel.isBlank() && action.actionType in setOf(AgentActionType.TAP, AgentActionType.INPUT_TEXT)) {
            return null
        }

        val goalKeywords = normalizeGoalKeywords(candidate.goal)
        val cacheKey = buildCacheKey(goalKeywords, candidate.packageName, candidate.screenSignature)
        val now = System.currentTimeMillis()

        val entry = CachedActionEntry(
            cacheKey = cacheKey,
            goalKeywords = goalKeywords,
            packageName = candidate.packageName,
            screenSignature = candidate.screenSignature,
            actionType = action.actionType,
            targetLabel = targetLabel,
            targetViewId = node?.viewId.orEmpty(),
            tapX = node?.bounds?.centerX ?: action.x,
            tapY = node?.bounds?.centerY ?: action.y,
            textToType = action.textToType,
            hitCount = 1,
            lastUsedMs = now
        )

        val current = _cachedActions.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.cacheKey == cacheKey }
        val saved = if (existingIdx >= 0) {
            val prev = current[existingIdx]
            val merged = entry.copy(hitCount = prev.hitCount + 1)
            current[existingIdx] = merged
            merged
        } else {
            current.add(0, entry)
            if (current.size > MAX_CACHED_ACTIONS) {
                current.subList(MAX_CACHED_ACTIONS, current.size).clear()
            }
            entry
        }

        val sorted = current.sortedByDescending { it.lastUsedMs }
        _cachedActions.value = sorted
        persist(sorted)
        return saved
    }

    suspend fun incrementHit(cacheKey: String) {
        val current = _cachedActions.value.toMutableList()
        val idx = current.indexOfFirst { it.cacheKey == cacheKey }
        if (idx < 0) return
        val existing = current[idx]
        current[idx] = existing.copy(
            hitCount = existing.hitCount + 1,
            lastUsedMs = System.currentTimeMillis()
        )
        val sorted = current.sortedByDescending { it.lastUsedMs }
        _cachedActions.value = sorted
        persist(sorted)
    }

    suspend fun removeEntry(cacheKey: String) {
        val updated = _cachedActions.value.filterNot { it.cacheKey == cacheKey }
        _cachedActions.value = updated
        persist(updated)
    }

    suspend fun clearAll() {
        _cachedActions.value = emptyList()
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_CACHE_JSON).apply()
        }
    }

    private suspend fun persist(list: List<CachedActionEntry>) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_CACHE_JSON, serializeJson(list)).apply()
        }
    }

    companion object {
        private val cacheableActions = setOf(
            AgentActionType.TAP,
            AgentActionType.INPUT_TEXT,
            AgentActionType.SCROLL_DOWN,
            AgentActionType.SCROLL_UP
        )

        private val stopWords = setOf(
            "the", "and", "for", "with", "into", "from", "then", "open", "app", "please",
            "game", "tap", "click", "on", "to", "a", "an", "in", "of"
        )

        fun isEligibleForCache(
            candidate: PendingCacheCandidate,
            afterSnapshot: UiSnapshot
        ): Boolean {
            if (candidate.hadConfirmedSafety || candidate.action.requiresConfirmation) return false
            if (candidate.action.actionType !in cacheableActions) return false
            if (candidate.action.foulOrMistakeLesson != null) return false
            if (candidate.interactiveNodeCount < MIN_INTERACTIVE_NODES_FOR_CACHE) return false
            if (candidate.packageName.isBlank() || candidate.packageName == "unknown") return false

            // Confirm the action actually changed package or UI screen state
            val afterSig = computeScreenSignature(afterSnapshot.nodes)
            val changedScreen = afterSnapshot.packageName != candidate.packageName ||
                afterSig != candidate.screenSignature
            if (!changedScreen) return false

            if (candidate.action.actionType in setOf(AgentActionType.TAP, AgentActionType.INPUT_TEXT)) {
                val node = candidate.targetNode ?: return false
                if (node.displayLabel().isBlank() && node.viewId.isBlank()) return false
            }
            return true
        }

        fun matchEntryFromList(
            entries: List<CachedActionEntry>,
            goal: String,
            snapshot: UiSnapshot
        ): CacheReplayMatch? {
            if (entries.isEmpty()) return null
            if (snapshot.nodes.count { it.isClickable || it.isEditable } < MIN_INTERACTIVE_NODES_FOR_CACHE) {
                return null
            }

            val currentSig = computeScreenSignature(snapshot.nodes)
            val currentGoalKeys = normalizeGoalKeywords(goal)
            val exactKey = buildCacheKey(currentGoalKeys, snapshot.packageName, currentSig)

            val matched = entries.firstOrNull { it.cacheKey == exactKey }
                ?: entries.firstOrNull { entry ->
                    entry.packageName == snapshot.packageName &&
                        entry.screenSignature == currentSig &&
                        goalsOverlap(currentGoalKeys, entry.goalKeywords)
                }
                ?: return null

            return resolveLiveReplayAction(matched, snapshot)
        }

        internal fun resolveLiveReplayAction(
            entry: CachedActionEntry,
            snapshot: UiSnapshot
        ): CacheReplayMatch? {
            return when (entry.actionType) {
                AgentActionType.SCROLL_DOWN, AgentActionType.SCROLL_UP -> {
                    CacheReplayMatch(
                        entry = entry,
                        action = PlannedAction(
                            actionType = entry.actionType,
                            targetNodeId = null,
                            x = null,
                            y = null,
                            textToType = null,
                            waitSeconds = null,
                            memoryNote = null,
                            reason = "Zero-API Cache Replay: ${entry.targetLabel}",
                            requiresConfirmation = false
                        ),
                        resolvedSummary = entry.targetLabel
                    )
                }
                AgentActionType.TAP, AgentActionType.INPUT_TEXT -> {
                    val liveNode = findMatchingLiveNode(entry, snapshot.nodes) ?: return null
                    CacheReplayMatch(
                        entry = entry,
                        action = PlannedAction(
                            actionType = entry.actionType,
                            targetNodeId = liveNode.id,
                            x = liveNode.bounds.centerX,
                            y = liveNode.bounds.centerY,
                            textToType = entry.textToType,
                            waitSeconds = null,
                            memoryNote = null,
                            reason = "Zero-API Cache Replay: ${liveNode.displayLabel()}",
                            requiresConfirmation = false
                        ),
                        resolvedSummary = "#${liveNode.id} ${liveNode.displayLabel()} (${liveNode.bounds.centerX},${liveNode.bounds.centerY})"
                    )
                }
                else -> null
            }
        }

        private fun findMatchingLiveNode(
            entry: CachedActionEntry,
            nodes: List<ScreenNode>
        ): ScreenNode? {
            if (entry.targetViewId.isNotBlank()) {
                val byViewId = nodes.firstOrNull {
                    it.viewId == entry.targetViewId &&
                        (entry.targetLabel.isBlank() || it.displayLabel().equals(entry.targetLabel, ignoreCase = true))
                } ?: nodes.firstOrNull { it.viewId == entry.targetViewId }
                if (byViewId != null) return byViewId
            }

            if (entry.targetLabel.isNotBlank()) {
                val matchingLabelNodes = nodes.filter {
                    (it.isClickable || it.isEditable) &&
                        it.displayLabel().equals(entry.targetLabel, ignoreCase = true)
                }
                if (matchingLabelNodes.size == 1) return matchingLabelNodes.first()
                if (matchingLabelNodes.size > 1 && entry.tapX != null && entry.tapY != null) {
                    return matchingLabelNodes.minByOrNull { n ->
                        val dx = n.bounds.centerX - entry.tapX
                        val dy = n.bounds.centerY - entry.tapY
                        dx * dx + dy * dy
                    }
                }
            }
            return null
        }

        fun computeScreenSignature(nodes: List<ScreenNode>): String {
            val tokens = nodes
                .filter { it.isClickable || it.isEditable || it.isScrollable }
                .mapNotNull { node ->
                    val raw = node.viewId.substringAfterLast('/')
                        .ifBlank { node.displayLabel() }
                        .trim()
                        .lowercase()
                    raw.takeIf { it.isNotBlank() }?.take(28)
                }
                .distinct()
                .take(14)

            if (tokens.isEmpty()) return "empty_canvas"
            return sha256Short(tokens.joinToString("|"))
        }

        fun normalizeGoalKeywords(goal: String): String {
            val words = goal.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 && it !in stopWords }
                .distinct()
                .take(5)
                .sorted()
            return if (words.isEmpty()) goal.trim().lowercase().take(40) else words.joinToString("_")
        }

        private fun goalsOverlap(a: String, b: String): Boolean {
            if (a == b) return true
            val setA = a.split('_').filter { it.isNotBlank() }.toSet()
            val setB = b.split('_').filter { it.isNotBlank() }.toSet()
            if (setA.isEmpty() || setB.isEmpty()) return false
            val common = setA.intersect(setB).size
            val minSize = minOf(setA.size, setB.size)
            return common >= 2 || (minSize == 1 && common == 1)
        }

        fun buildCacheKey(goalKeywords: String, packageName: String, screenSignature: String): String {
            return sha256Short("$goalKeywords::$packageName::$screenSignature")
        }

        private fun sha256Short(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.take(10).joinToString("") { "%02x".format(it) }
        }

        fun serializeJson(list: List<CachedActionEntry>): String {
            val arr = JSONArray()
            list.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("cacheKey", item.cacheKey)
                        put("goalKeywords", item.goalKeywords)
                        put("packageName", item.packageName)
                        put("screenSignature", item.screenSignature)
                        put("actionType", item.actionType.name)
                        put("targetLabel", item.targetLabel)
                        put("targetViewId", item.targetViewId)
                        put("tapX", item.tapX ?: JSONObject.NULL)
                        put("tapY", item.tapY ?: JSONObject.NULL)
                        put("textToType", item.textToType ?: JSONObject.NULL)
                        put("hitCount", item.hitCount)
                        put("lastUsedMs", item.lastUsedMs)
                    }
                )
            }
            return arr.toString()
        }

        fun parseJson(raw: String?): List<CachedActionEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                val out = mutableListOf<CachedActionEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val cacheKey = obj.optString("cacheKey").trim()
                    val pkg = obj.optString("packageName").trim()
                    val sig = obj.optString("screenSignature").trim()
                    if (cacheKey.isEmpty() || pkg.isEmpty() || sig.isEmpty()) continue
                    out.add(
                        CachedActionEntry(
                            cacheKey = cacheKey,
                            goalKeywords = obj.optString("goalKeywords"),
                            packageName = pkg,
                            screenSignature = sig,
                            actionType = AgentActionType.fromWire(obj.optString("actionType", "TAP")),
                            targetLabel = obj.optString("targetLabel"),
                            targetViewId = obj.optString("targetViewId"),
                            tapX = if (obj.has("tapX") && !obj.isNull("tapX")) obj.optInt("tapX") else null,
                            tapY = if (obj.has("tapY") && !obj.isNull("tapY")) obj.optInt("tapY") else null,
                            textToType = if (obj.has("textToType") && !obj.isNull("textToType")) obj.optString("textToType") else null,
                            hitCount = obj.optInt("hitCount", 1).coerceAtLeast(1),
                            lastUsedMs = obj.optLong("lastUsedMs", System.currentTimeMillis())
                        )
                    )
                }
                out
            }.getOrDefault(emptyList())
        }
    }
}
