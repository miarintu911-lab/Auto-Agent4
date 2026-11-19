package com.example.agent

import java.util.Locale

object ChatTurnGuard {

    private val MESSAGING_PACKAGES = setOf(
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "org.telegram.messenger",
        "com.imo.android.imoim",
        "com.viber.voip"
    )

    private val HEADER_IGNORE_TOKENS = setOf(
        "back", "navigate up", "call", "voice call", "audio call", "video call",
        "info", "more", "more options", "options", "menu", "search", "camera",
        "active now", "online", "offline", "typing", "typing...", "last seen",
        "messenger", "whatsapp", "whatsapp business", "facebook", "chats", "chat",
        "stories", "people", "reels", "notifications", "settings", "home",
        "ফিরে যান", "কল", "ভিডিও কল", "খুঁজুন", "অপশন"
    )

    private val STATUS_OR_RECEIPT_TOKENS = setOf(
        "seen", "delivered", "sent", "sending", "sending...", "read", "active now",
        "today", "yesterday", "typing...", "you", "wave", "missed call",
        "delivered.", "seen.", "sent.", "দেখেছে", "পাঠানো হয়েছে", "আজ", "গতকাল"
    )

    private val SEND_KEYWORDS = listOf(
        "send", "পাঠান", "ic_send", "send_btn", "send_button", "msg_send",
        "reply", "submit", "post"
    )

    private val TIME_ONLY_REGEX = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|am|pm))?$")
    private val PERCENT_ONLY_REGEX = Regex("^\\d{1,3}%$")
    private val COMPOSE_HINT_REGEX = Regex("(message|type a message|aa|write a message|মেসেজ|বার্তা)", RegexOption.IGNORE_CASE)

    data class ChatObservation(
        val isMessagingApp: Boolean,
        val inChatThread: Boolean,
        val packageName: String,
        val peerName: String,
        val transcriptLines: List<String>,
        val lastMessageText: String,
        val transcriptSignature: String
    )

    data class AwaitingReplyState(
        val packageName: String,
        val peerName: String,
        val lastSentText: String,
        val transcriptSignatureAtSend: String,
        val sentAtMs: Long
    )

    fun isMessagingPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (pkg in MESSAGING_PACKAGES) return true
        return pkg.contains("whatsapp") ||
            pkg.contains("orca") ||
            pkg.contains("mlite") ||
            pkg.contains("messenger") ||
            pkg.contains("telegram")
    }

    fun peerKey(packageName: String, peerName: String): String {
        val pkgNorm = normalizeFamilyPackage(packageName)
        val peerNorm = peerName.trim().lowercase(Locale.ROOT)
        return "$pkgNorm::$peerNorm"
    }

    private fun normalizeFamilyPackage(packageName: String): String {
        val pkg = packageName.lowercase(Locale.ROOT)
        return when {
            pkg.contains("whatsapp") -> "whatsapp"
            pkg.contains("orca") || pkg.contains("mlite") || pkg.contains("facebook") -> "facebook_messenger"
            else -> pkg
        }
    }

    fun extractChatObservation(snapshot: UiSnapshot): ChatObservation {
        if (!isMessagingPackage(snapshot.packageName) || snapshot.screenHeight <= 0 || snapshot.screenWidth <= 0) {
            return ChatObservation(
                isMessagingApp = false,
                inChatThread = false,
                packageName = snapshot.packageName,
                peerName = "",
                transcriptLines = emptyList(),
                lastMessageText = "",
                transcriptSignature = ""
            )
        }

        val h = snapshot.screenHeight.toFloat()
        val w = snapshot.screenWidth.toFloat()

        val hasComposeBox = snapshot.nodes.any { node ->
            val inBottomHalf = node.bounds.top >= h * 0.50f
            val composeHint = COMPOSE_HINT_REGEX.containsMatchIn(node.text) ||
                COMPOSE_HINT_REGEX.containsMatchIn(node.contentDesc) ||
                node.viewId.contains("entry", ignoreCase = true) ||
                node.viewId.contains("composer", ignoreCase = true) ||
                node.viewId.contains("input", ignoreCase = true)
            inBottomHalf && (node.isEditable || composeHint)
        }

        // Locate peer title in top header bar (skip status bar and trailing action icons)
        val headerCandidate = snapshot.nodes
            .asSequence()
            .filter { node ->
                !node.isEditable &&
                    node.bounds.top >= (h * 0.02f) &&
                    node.bounds.bottom <= (h * 0.22f) &&
                    node.bounds.left >= (w * 0.08f) &&
                    node.bounds.left <= (w * 0.72f)
            }
            .mapNotNull { node ->
                val raw = node.text.trim().ifBlank { node.contentDesc.trim() }
                val clean = raw.trim()
                if (clean.length !in 2..48) return@mapNotNull null
                if (isIgnoredHeaderText(clean)) return@mapNotNull null
                val score = buildHeaderScore(node, clean, h)
                clean to score
            }
            .maxByOrNull { it.second }
            ?.first
            .orEmpty()

        // Collect message transcript bubbles inside the middle region
        val transcriptLines = snapshot.nodes
            .asSequence()
            .filter { node ->
                !node.isEditable &&
                    node.bounds.top >= (h * 0.16f) &&
                    node.bounds.bottom <= (h * 0.87f)
            }
            .sortedWith(compareBy<ScreenNode> { it.bounds.top }.thenBy { it.bounds.left })
            .mapNotNull { node ->
                val raw = node.text.trim()
                if (raw.isBlank()) return@mapNotNull null
                if (raw.equals(headerCandidate, ignoreCase = true)) return@mapNotNull null
                if (isReceiptOrNoise(raw)) return@mapNotNull null
                if (COMPOSE_HINT_REGEX.matches(raw)) return@mapNotNull null
                raw
            }
            .distinct()
            .toList()

        val lastMessage = transcriptLines.lastOrNull().orEmpty()
        val sig = transcriptLines.takeLast(8).joinToString("|").hashCode().toString()

        return ChatObservation(
            isMessagingApp = true,
            inChatThread = hasComposeBox,
            packageName = snapshot.packageName,
            peerName = headerCandidate,
            transcriptLines = transcriptLines,
            lastMessageText = lastMessage,
            transcriptSignature = sig
        )
    }

    fun isChatSendAction(
        action: PlannedAction,
        targetNode: ScreenNode?,
        snapshot: UiSnapshot
    ): Boolean {
        if (!isMessagingPackage(snapshot.packageName)) return false
        if (action.actionType == AgentActionType.INPUT_TEXT) return true
        if (action.actionType != AgentActionType.TAP) return false

        val combined = listOfNotNull(
            targetNode?.text,
            targetNode?.contentDesc,
            targetNode?.viewId,
            action.reason
        ).joinToString(" ").lowercase(Locale.ROOT)

        if (SEND_KEYWORDS.any { combined.contains(it) }) {
            return true
        }

        // Bottom-right icon tap next to a compose field is almost always the Send button
        val tapX = action.x ?: targetNode?.bounds?.centerX
        val tapY = action.y ?: targetNode?.bounds?.centerY
        val hasBottomCompose = snapshot.nodes.any {
            it.isEditable && it.bounds.top > snapshot.screenHeight * 0.45f
        }
        return hasBottomCompose &&
            tapX != null && tapY != null &&
            tapX > snapshot.screenWidth * 0.78f &&
            tapY > snapshot.screenHeight * 0.45f
    }

    fun didIncomingReplyArrive(
        obs: ChatObservation,
        recentNotifications: List<NotificationSignal>,
        awaiting: AwaitingReplyState
    ): Boolean {
        // 1. Check fresh incoming notification from a messaging app matching this peer
        val notifReply = recentNotifications.any { sig ->
            if (sig.timestampMs <= awaiting.sentAtMs) return@any false
            if (!isMessagingPackage(sig.packageName)) return@any false
            val textLower = sig.text.lowercase(Locale.ROOT)
            if (textLower.contains("sending") || textLower.contains("tappilot")) return@any false
            if (awaiting.peerName.isBlank()) {
                true
            } else {
                val peerLower = awaiting.peerName.lowercase(Locale.ROOT)
                textLower.contains(peerLower) ||
                    peerLower.split(" ").any { part -> part.length >= 3 && textLower.contains(part) }
            }
        }
        if (notifReply) return true

        // 2. Check visible chat transcript on screen for the same peer
        if (!obs.isMessagingApp || !obs.inChatThread) return false
        val samePeer = samePeerContext(obs.packageName, obs.peerName, awaiting.packageName, awaiting.peerName)
        if (!samePeer) return false
        if (obs.lastMessageText.isBlank()) return false
        if (obs.transcriptSignature == awaiting.transcriptSignatureAtSend) return false

        // Make sure we do NOT mistake our own newly rendered sent bubble for a reply!
        if (matchesOwnSentMessage(obs.lastMessageText, awaiting.lastSentText)) {
            return false
        }

        return true
    }

    fun shouldBlockDuplicateSend(
        action: PlannedAction,
        targetNode: ScreenNode?,
        obs: ChatObservation,
        recentNotifications: List<NotificationSignal>,
        awaiting: AwaitingReplyState?,
        snapshot: UiSnapshot
    ): Boolean {
        if (awaiting == null) return false
        if (!obs.isMessagingApp || !obs.inChatThread) return false
        if (!samePeerContext(obs.packageName, obs.peerName, awaiting.packageName, awaiting.peerName)) {
            return false
        }
        if (didIncomingReplyArrive(obs, recentNotifications, awaiting)) {
            return false
        }
        return isChatSendAction(action, targetNode, snapshot)
    }

    fun matchesOwnSentMessage(visibleBubble: String, sentText: String): Boolean {
        val b = normalizeMessageText(visibleBubble)
        val s = normalizeMessageText(sentText)
        if (b.isBlank() || s.isBlank()) return false
        return b == s || b.contains(s) || (b.length >= 6 && s.contains(b))
    }

    private fun samePeerContext(
        pkgA: String,
        peerA: String,
        pkgB: String,
        peerB: String
    ): Boolean {
        if (normalizeFamilyPackage(pkgA) != normalizeFamilyPackage(pkgB)) return false
        val a = peerA.trim().lowercase(Locale.ROOT)
        val b = peerB.trim().lowercase(Locale.ROOT)
        if (a.isBlank() || b.isBlank()) return true
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun normalizeMessageText(text: String): String {
        return text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
    }

    private fun isIgnoredHeaderText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (lower in HEADER_IGNORE_TOKENS) return true
        if (TIME_ONLY_REGEX.matches(text)) return true
        if (PERCENT_ONLY_REGEX.matches(text)) return true
        if (lower.startsWith("active ") && lower.contains("ago")) return true
        if (lower.startsWith("last seen")) return true
        return false
    }

    private fun isReceiptOrNoise(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower in STATUS_OR_RECEIPT_TOKENS) return true
        if (TIME_ONLY_REGEX.matches(text.trim())) return true
        if (lower.startsWith("seen by") || lower.startsWith("delivered") || lower.startsWith("sent ")) {
            return true
        }
        return false
    }

    private fun buildHeaderScore(node: ScreenNode, clean: String, screenHeight: Float): Int {
        var score = 0
        if (node.text.isNotBlank()) score += 20
        if (node.bounds.top >= screenHeight * 0.04f && node.bounds.top <= screenHeight * 0.14f) score += 15
        if (!node.isClickable) score += 5
        if (clean.any { it.isLetter() }) score += 10
        if (node.viewId.contains("title", ignoreCase = true) ||
            node.viewId.contains("name", ignoreCase = true) ||
            node.viewId.contains("header", ignoreCase = true)
        ) {
            score += 20
        }
        return score
    }
}
