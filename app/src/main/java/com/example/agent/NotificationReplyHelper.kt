package com.example.agent

import java.util.Locale

data class NotificationReplyTarget(
    val targetKey: String,
    val packageName: String,
    val appDisplayName: String,
    val senderName: String,
    val messageText: String,
    val receivedAtMs: Long
)

object NotificationReplyHelper {

    private val APP_NAMES_LOWER = setOf(
        "whatsapp", "wa business", "whatsapp business", "messenger",
        "facebook", "facebook lite", "lite", "instagram", "telegram", "imo", "viber"
    )

    private val NOISE_BODY_TOKENS = listOf(
        "checking for new messages",
        "backup in progress",
        "restoring media",
        "finished backup",
        "whatsapp web is currently active",
        "chat heads active",
        "tap to launch",
        "ongoing call",
        "incoming voice call",
        "incoming video call",
        "missed voice call",
        "missed video call",
        "tappilot",
        "নতুন মেসেজ দেখা হচ্ছে",
        "ব্যাকআপ"
    )

    private val SUMMARY_COUNT_REGEX = Regex(
        "^\\d+\\s+(new\\s+)?messages?(\\s+from\\s+\\d+\\s+chats?)?$",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_MSG_BADGE_REGEX = Regex(
        "\\s*\\(\\d+\\s*(messages?|unread|নতুন মেসেজ)?\\)\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun parseNotification(
        packageName: String,
        title: String?,
        body: String?,
        conversationTitle: String?,
        rawFallbackText: String,
        timestampMs: Long,
        isGroupSummary: Boolean = false
    ): NotificationSignal? {
        if (packageName.isBlank() || isGroupSummary) return null

        val rawTitle = conversationTitle?.trim().orEmpty()
            .ifBlank { title?.trim().orEmpty() }
        val cleanSender = rawTitle
            .replace(TRAILING_MSG_BADGE_REGEX, "")
            .trim()
            .take(64)

        val cleanBody = body?.trim().orEmpty()
            .ifBlank { rawFallbackText.trim() }
            .take(180)

        if (isNoisyOrSystemNotification(packageName, cleanSender, cleanBody)) {
            return null
        }

        val combined = listOf(cleanSender, cleanBody)
            .filter { it.isNotBlank() }
            .joinToString(": ")
            .ifBlank { rawFallbackText.trim() }
            .take(240)

        if (combined.isBlank()) return null

        return NotificationSignal(
            packageName = packageName,
            text = combined,
            timestampMs = timestampMs,
            senderName = cleanSender,
            messageBody = cleanBody
        )
    }

    fun isNoisyOrSystemNotification(
        packageName: String,
        senderName: String,
        body: String
    ): Boolean {
        val senderLower = senderName.trim().lowercase(Locale.ROOT)
        val bodyLower = body.trim().lowercase(Locale.ROOT)
        val combinedLower = "$senderLower $bodyLower".trim()
        if (combinedLower.isBlank()) return true

        if (NOISE_BODY_TOKENS.any { combinedLower.contains(it) }) return true
        if (SUMMARY_COUNT_REGEX.matches(bodyLower)) return true
        if (senderLower in APP_NAMES_LOWER &&
            (bodyLower.contains("new message") || bodyLower.contains("unread") || bodyLower.isBlank())
        ) {
            return true
        }
        return false
    }

    fun isNotificationDrivenAutoReplyGoal(
        goal: String,
        mode: AgentExecutionMode,
        settings: AgentSettings
    ): Boolean {
        if (!settings.replyOnlyOnMessageNotifications) return false
        if (CarromContextHelper.isCarromGoalOrApp(goal, "", null)) return false

        val lower = goal.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false

        val hasAutoOrNotifTrigger = lower.contains("auto") ||
            lower.contains("অটো") ||
            lower.contains("মেসেজ আসলে") ||
            lower.contains("মেসেজ এলে") ||
            lower.contains("notification") ||
            lower.contains("নোটিফিকেশন") ||
            lower.contains("incoming")

        val hasReplyOrChatIntent = lower.contains("মেসেজ") ||
            lower.contains("message") ||
            lower.contains("reply") ||
            lower.contains("রিপ্লাই") ||
            lower.contains("কথা বল") ||
            lower.contains("chat") ||
            lower.contains("whatsapp") ||
            lower.contains("wa business") ||
            lower.contains("messenger") ||
            lower.contains("রিলেশনশিপ") ||
            lower.contains("relationship")

        val hasNonChatOutboundSearchOrPost = lower.contains("search for") ||
            lower.contains("post in")

        val isPassiveMonitor = mode == AgentExecutionMode.CONTINUOUS_MONITOR &&
            (lower.contains("monitor") || lower.contains("watch") || hasAutoOrNotifTrigger)

        return (hasAutoOrNotifTrigger || isPassiveMonitor) &&
            hasReplyOrChatIntent &&
            !hasNonChatOutboundSearchOrPost
    }

    fun appSearchNameForPackage(packageName: String): String? {
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        return when {
            pkg == "com.whatsapp.w4b" || pkg.contains("w4b") || pkg.contains("whatsapp.business") -> "WA Business"
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg == "com.facebook.orca" || pkg == "com.facebook.mlite" || pkg.contains("messenger") -> "Messenger"
            pkg == "com.facebook.lite" -> "Lite"
            pkg == "com.facebook.katana" -> "Facebook"
            pkg == "com.instagram.android" -> "Instagram"
            pkg == "org.telegram.messenger" -> "Telegram"
            pkg == "com.imo.android.imoim" -> "imo"
            else -> null
        }
    }

    fun extractNewTargets(
        notifications: List<NotificationSignal>,
        minTimestampMs: Long,
        handledNotificationKeys: Set<String>,
        alreadyQueuedOrActive: List<NotificationReplyTarget>
    ): List<NotificationReplyTarget> {
        val results = mutableListOf<NotificationReplyTarget>()
        for (sig in notifications) {
            if (sig.timestampMs < minTimestampMs) continue
            if (!ChatTurnGuard.isMessagingPackage(sig.packageName)) continue

            val sender = sig.senderName.ifBlank {
                sig.text.substringBefore(':').trim()
                    .replace(TRAILING_MSG_BADGE_REGEX, "")
                    .trim()
            }
            val body = sig.messageBody.ifBlank {
                if (sig.text.contains(':')) sig.text.substringAfter(':').trim() else sig.text.trim()
            }
            if (sender.isBlank() || isNoisyOrSystemNotification(sig.packageName, sender, body)) {
                continue
            }

            val notifKey = "${ChatTurnGuard.peerKey(sig.packageName, sender)}@${sig.timestampMs}"
            if (notifKey in handledNotificationKeys) continue

            val peerId = ChatTurnGuard.peerKey(sig.packageName, sender)
            val alreadyTracked = alreadyQueuedOrActive.any {
                ChatTurnGuard.peerKey(it.packageName, it.senderName) == peerId
            } || results.any {
                ChatTurnGuard.peerKey(it.packageName, it.senderName) == peerId
            }
            if (alreadyTracked) continue

            val appName = appSearchNameForPackage(sig.packageName) ?: continue
            results.add(
                NotificationReplyTarget(
                    targetKey = notifKey,
                    packageName = sig.packageName,
                    appDisplayName = appName,
                    senderName = sender,
                    messageText = body,
                    receivedAtMs = sig.timestampMs
                )
            )
        }
        return results
    }

    fun matchesTargetPeer(observedPeerName: String, targetSenderName: String): Boolean {
        val obs = observedPeerName.trim().lowercase(Locale.ROOT)
        val tgt = targetSenderName.trim().lowercase(Locale.ROOT)
        if (obs.isBlank() || tgt.isBlank()) return false
        if (obs == tgt || obs.contains(tgt) || tgt.contains(obs)) return true

        val obsTokens = obs.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        val tgtTokens = tgt.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        return obsTokens.intersect(tgtTokens).isNotEmpty()
    }

    fun buildRelationshipCheckSummary(
        target: NotificationReplyTarget?,
        matchedContact: ContactProfile?
    ): String {
        if (target == null && matchedContact == null) return ""
        return if (matchedContact != null) {
            val notePart = if (matchedContact.customStyleNote.isNotBlank()) {
                " Note: ${matchedContact.customStyleNote}"
            } else {
                ""
            }
            "RELATIONSHIP CHECK: Found '${matchedContact.name}' [${matchedContact.platform.label}] -> ${matchedContact.relationship.label}. Strictly talk according to this saved relationship.$notePart"
        } else {
            val who = target?.senderName?.takeIf { it.isNotBlank() } ?: "this sender"
            "RELATIONSHIP CHECK: No relationship saved in app for '$who' -> Speak politely, warmly, and nicely (সুন্দর ও ভদ্রভাবে কথা বলো) according to their message."
        }
    }
}
