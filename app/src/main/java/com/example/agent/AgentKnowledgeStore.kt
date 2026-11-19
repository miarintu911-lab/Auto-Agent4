package com.example.agent

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
import java.io.File
import java.util.Locale

private const val PLAYBOOK_FILE_NAME = "tappilot_playbooks.json"
private const val MAX_PLAYBOOKS = 24
private const val MAX_LESSONS_PER_CATEGORY = 12

class AgentKnowledgeStore(context: Context) {

    private val storageFile = File(context.filesDir, PLAYBOOK_FILE_NAME)
    private val mutex = Mutex()

    private val _playbooks = MutableStateFlow<List<TaskPlaybook>>(emptyList())
    val playbooks: StateFlow<List<TaskPlaybook>> = _playbooks.asStateFlow()

    suspend fun loadInitial() {
        mutex.withLock {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!storageFile.exists()) emptyList()
                    else parsePlaybooksJson(storageFile.readText())
                }.getOrDefault(emptyList())
            }
            _playbooks.value = loaded
        }
    }

    fun findMatchingPlaybook(goal: String): TaskPlaybook? {
        return findBestMatchingPlaybook(_playbooks.value, goal)
    }

    suspend fun saveOrMergePlaybook(playbook: TaskPlaybook): TaskPlaybook {
        return mutex.withLock {
            val current = _playbooks.value.toMutableList()
            val normalizedKey = normalizeKey(playbook.topicKey.ifBlank { playbook.topicTitle })
            val existingIdx = current.indexOfFirst { normalizeKey(it.topicKey) == normalizedKey }

            val merged = if (existingIdx >= 0) {
                val existing = current[existingIdx]
                existing.copy(
                    topicTitle = playbook.topicTitle.ifBlank { existing.topicTitle },
                    rulesSummary = playbook.rulesSummary.ifBlank { existing.rulesSummary },
                    winningStrategies = mergeLessons(existing.winningStrategies, playbook.winningStrategies),
                    foulLessons = mergeLessons(existing.foulLessons, playbook.foulLessons),
                    trainingSessions = maxOf(existing.trainingSessions, playbook.trainingSessions),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            } else {
                playbook.copy(
                    topicKey = normalizedKey,
                    winningStrategies = mergeLessons(emptyList(), playbook.winningStrategies),
                    foulLessons = mergeLessons(emptyList(), playbook.foulLessons),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            }

            if (existingIdx >= 0) {
                current[existingIdx] = merged
            } else {
                current.add(0, merged)
            }

            val trimmed = current
                .sortedByDescending { it.lastUpdatedMs }
                .take(MAX_PLAYBOOKS)

            persistLocked(trimmed)
            merged
        }
    }

    suspend fun recordStepLearning(
        topicKey: String,
        fallbackTopicTitle: String,
        newStrategy: String?,
        newFoulLesson: String?
    ): TaskPlaybook? {
        val cleanStrategy = newStrategy?.trim()?.takeIf { it.isNotBlank() }
        val cleanFoul = newFoulLesson?.trim()?.takeIf { it.isNotBlank() }
        if (cleanStrategy == null && cleanFoul == null) return null

        return mutex.withLock {
            val current = _playbooks.value.toMutableList()
            val normKey = normalizeKey(topicKey)
            val idx = current.indexOfFirst { normalizeKey(it.topicKey) == normKey }

            val updated = if (idx >= 0) {
                val existing = current[idx]
                existing.copy(
                    winningStrategies = mergeLessons(existing.winningStrategies, listOfNotNull(cleanStrategy)),
                    foulLessons = mergeLessons(existing.foulLessons, listOfNotNull(cleanFoul)),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            } else {
                TaskPlaybook(
                    topicKey = normKey,
                    topicTitle = fallbackTopicTitle.take(48).ifBlank { "Task Skill" },
                    rulesSummary = "Learned dynamically through live execution and visual feedback.",
                    winningStrategies = listOfNotNull(cleanStrategy),
                    foulLessons = listOfNotNull(cleanFoul),
                    trainingSessions = 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            }

            if (idx >= 0) current[idx] = updated else current.add(0, updated)
            persistLocked(current.sortedByDescending { it.lastUpdatedMs }.take(MAX_PLAYBOOKS))
            updated
        }
    }

    suspend fun incrementTrainingSession(topicKey: String) {
        mutex.withLock {
            val current = _playbooks.value.toMutableList()
            val normKey = normalizeKey(topicKey)
            val idx = current.indexOfFirst { normalizeKey(it.topicKey) == normKey }
            if (idx >= 0) {
                val existing = current[idx]
                current[idx] = existing.copy(
                    trainingSessions = existing.trainingSessions + 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
                persistLocked(current.sortedByDescending { it.lastUpdatedMs })
            }
        }
    }

    suspend fun deletePlaybook(topicKey: String) {
        mutex.withLock {
            val normKey = normalizeKey(topicKey)
            val filtered = _playbooks.value.filterNot { normalizeKey(it.topicKey) == normKey }
            persistLocked(filtered)
        }
    }

    private suspend fun persistLocked(items: List<TaskPlaybook>) {
        _playbooks.value = items
        withContext(Dispatchers.IO) {
            runCatching {
                storageFile.writeText(serializePlaybooksJson(items))
            }
        }
    }

    companion object {
        fun normalizeKey(raw: String): String {
            return raw.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "general_task" }
        }

        fun mergeLessons(existing: List<String>, additions: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<String>()
            (existing + additions).forEach { raw ->
                val clean = raw.trim()
                val canonical = clean.lowercase(Locale.ROOT)
                if (clean.isNotBlank() && seen.add(canonical)) {
                    result.add(clean)
                }
            }
            return result.takeLast(MAX_LESSONS_PER_CATEGORY)
        }

        fun findBestMatchingPlaybook(playbooks: List<TaskPlaybook>, goal: String): TaskPlaybook? {
            if (playbooks.isEmpty() || goal.isBlank()) return null
            val goalNorm = goal.lowercase(Locale.ROOT)
            val goalTokens = tokenize(goalNorm)
            if (goalTokens.isEmpty()) return null

            var best: TaskPlaybook? = null
            var bestScore = 0
            for (pb in playbooks) {
                val keyTokens = tokenize(pb.topicKey.replace('_', ' ')) + tokenize(pb.topicTitle)
                val overlap = goalTokens.count { token ->
                    keyTokens.any { it == token || (token.length >= 4 && it.contains(token)) || (it.length >= 4 && token.contains(it)) }
                }
                val directPhrase = goalNorm.contains(pb.topicTitle.lowercase(Locale.ROOT))
                val score = overlap + if (directPhrase) 3 else 0
                if (score > bestScore && (score >= 2 || directPhrase)) {
                    bestScore = score
                    best = pb
                }
            }
            return best
        }

        private fun tokenize(text: String): Set<String> {
            val stopWords = setOf(
                "the", "and", "for", "with", "from", "into", "this", "that", "play", "open",
                "game", "app", "task", "please", "agent", "how", "will", "you", "can", "about"
            )
            return text.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
                .filter { it.length >= 3 && it !in stopWords }
                .toSet()
        }

        fun serializePlaybooksJson(playbooks: List<TaskPlaybook>): String {
            val array = JSONArray()
            for (pb in playbooks) {
                val obj = JSONObject()
                    .put("topicKey", pb.topicKey)
                    .put("topicTitle", pb.topicTitle)
                    .put("rulesSummary", pb.rulesSummary)
                    .put("winningStrategies", JSONArray(pb.winningStrategies))
                    .put("foulLessons", JSONArray(pb.foulLessons))
                    .put("trainingSessions", pb.trainingSessions)
                    .put("lastUpdatedMs", pb.lastUpdatedMs)
                array.put(obj)
            }
            return array.toString(2)
        }

        fun parsePlaybooksJson(raw: String): List<TaskPlaybook> {
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            val list = mutableListOf<TaskPlaybook>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("topicKey").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("topicTitle").ifBlank { key }
                val rules = obj.optString("rulesSummary")
                val strategies = jsonArrayToList(obj.optJSONArray("winningStrategies"))
                val fouls = jsonArrayToList(obj.optJSONArray("foulLessons"))
                val sessions = obj.optInt("trainingSessions", 1).coerceAtLeast(1)
                val updated = obj.optLong("lastUpdatedMs", System.currentTimeMillis())
                list.add(
                    TaskPlaybook(
                        topicKey = key,
                        topicTitle = title,
                        rulesSummary = rules,
                        winningStrategies = strategies,
                        foulLessons = fouls,
                        trainingSessions = sessions,
                        lastUpdatedMs = updated
                    )
                )
            }
            return list
        }

        private fun jsonArrayToList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val items = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) items.add(value)
            }
            return items
        }
    }
}
