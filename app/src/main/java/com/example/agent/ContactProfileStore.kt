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
import java.util.UUID

private const val CONTACTS_FILE_NAME = "tappilot_contacts.json"
private const val MAX_CONTACTS = 100

class ContactProfileStore(context: Context) {

    private val storageFile = File(context.filesDir, CONTACTS_FILE_NAME)
    private val mutex = Mutex()

    private val _contacts = MutableStateFlow<List<ContactProfile>>(emptyList())
    val contacts: StateFlow<List<ContactProfile>> = _contacts.asStateFlow()

    suspend fun loadInitial() {
        mutex.withLock {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!storageFile.exists()) emptyList()
                    else parseJson(storageFile.readText())
                }.getOrDefault(emptyList())
            }
            _contacts.value = loaded
        }
    }

    suspend fun upsertContact(profile: ContactProfile): ContactProfile {
        val cleanName = profile.name.trim()
        require(cleanName.isNotBlank()) { "Contact name cannot be empty." }
        val normalizedProfile = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
            name = cleanName,
            customStyleNote = profile.customStyleNote.trim()
        )
        return mutex.withLock {
            val current = _contacts.value.toMutableList()
            val idx = current.indexOfFirst { it.id == normalizedProfile.id }
            if (idx >= 0) {
                current[idx] = normalizedProfile
            } else {
                current.add(0, normalizedProfile)
            }
            val trimmed = current
                .sortedByDescending { it.createdAtMs }
                .take(MAX_CONTACTS)
            persistLocked(trimmed)
            normalizedProfile
        }
    }

    suspend fun deleteContact(id: String) {
        mutex.withLock {
            val filtered = _contacts.value.filterNot { it.id == id }
            persistLocked(filtered)
        }
    }

    private suspend fun persistLocked(items: List<ContactProfile>) {
        _contacts.value = items
        withContext(Dispatchers.IO) {
            runCatching {
                storageFile.writeText(serializeJson(items))
            }
        }
    }

    companion object {
        fun matchContactFromList(
            contacts: List<ContactProfile>,
            peerName: String,
            packageName: String,
            goal: String,
            recentNotifications: List<NotificationSignal>
        ): ContactProfile? {
            if (contacts.isEmpty()) return null
            val cleanPeer = peerName.trim().lowercase(Locale.ROOT)
            val cleanGoal = goal.trim().lowercase(Locale.ROOT)
            val messagingNotifs = recentNotifications
                .filter { ChatTurnGuard.isMessagingPackage(it.packageName) }
                .takeLast(5)
                .map { it.text.lowercase(Locale.ROOT) }

            var bestMatch: ContactProfile? = null
            var bestScore = 0

            for (contact in contacts) {
                if (!contact.autoReplyEnabled) continue
                val contactName = contact.name.trim().lowercase(Locale.ROOT)
                if (contactName.length < 2) continue

                val platformMatches = packageName.isBlank() || contact.platform.matchesPackage(packageName)
                if (!platformMatches) continue

                val platformSpecificityBonus = when (contact.platform) {
                    ChatPlatform.WHATSAPP, ChatPlatform.FACEBOOK_MESSENGER -> 3
                    ChatPlatform.BOTH -> 2
                    ChatPlatform.OTHER -> 1
                }

                var score = 0
                if (cleanPeer.isNotBlank()) {
                    when {
                        cleanPeer == contactName -> score += 100 + platformSpecificityBonus
                        cleanPeer.contains(contactName) || contactName.contains(cleanPeer) ->
                            score += 75 + platformSpecificityBonus
                        hasWordOverlap(cleanPeer, contactName) ->
                            score += 55 + platformSpecificityBonus
                    }
                }

                if (score == 0 && messagingNotifs.any { it.contains(contactName) }) {
                    score += 45 + platformSpecificityBonus
                }

                if (score == 0 && cleanGoal.contains(contactName)) {
                    score += 30 + platformSpecificityBonus
                }

                if (score > bestScore) {
                    bestScore = score
                    bestMatch = contact
                }
            }
            return bestMatch
        }

        fun formatRosterSummary(contacts: List<ContactProfile>): String {
            if (contacts.isEmpty()) return ""
            return contacts.take(15).joinToString("\n") { contact ->
                val note = if (contact.customStyleNote.isNotBlank()) " — Note: ${contact.customStyleNote}" else ""
                "- ${contact.name} [${contact.platform.label}] -> ${contact.relationship.label}$note"
            }
        }

        private fun hasWordOverlap(a: String, b: String): Boolean {
            val tokensA = a.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
            val tokensB = b.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
            return tokensA.intersect(tokensB).isNotEmpty()
        }

        fun serializeJson(contacts: List<ContactProfile>): String {
            val array = JSONArray()
            for (c in contacts) {
                val obj = JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("platform", c.platform.name)
                    .put("relationship", c.relationship.name)
                    .put("customStyleNote", c.customStyleNote)
                    .put("autoReplyEnabled", c.autoReplyEnabled)
                    .put("createdAtMs", c.createdAtMs)
                array.put(obj)
            }
            return array.toString(2)
        }

        fun parseJson(raw: String): List<ContactProfile> {
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            val result = mutableListOf<ContactProfile>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val name = obj.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                val platform = runCatching {
                    ChatPlatform.valueOf(obj.optString("platform", ChatPlatform.BOTH.name))
                }.getOrDefault(ChatPlatform.BOTH)
                val relationship = runCatching {
                    RelationshipType.valueOf(obj.optString("relationship", RelationshipType.FRIEND.name))
                }.getOrDefault(RelationshipType.FRIEND)
                val note = obj.optString("customStyleNote").trim()
                val autoReply = obj.optBoolean("autoReplyEnabled", true)
                val created = obj.optLong("createdAtMs", System.currentTimeMillis())
                result.add(
                    ContactProfile(
                        id = id,
                        name = name,
                        platform = platform,
                        relationship = relationship,
                        customStyleNote = note,
                        autoReplyEnabled = autoReply,
                        createdAtMs = created
                    )
                )
            }
            return result
        }
    }
}
