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

    const val ALL_PLATFORM_BG_GOAL: String =
        "Always-on All-Platform Notification Auto-Reply: stay running in background, when a message notification arrives from ANY platform open only that sender ID, check Relationship Contacts in this app first—if a relationship is saved talk in that exact relationship tone, otherwise keep talking simply, politely & naturally."

    private val APP_NAMES_LOWER = setOf(
        "whatsapp", "wa business", "whatsapp business", "messenger",
        "facebook", "facebook lite", "lite", "instagram", "telegram", "imo", "viber",
        "discord", "snapchat", "signal", "line", "messages", "wechat", "x", "threads",
        "tiktok", "linkedin", "skype"
    )

    private val SYSTEM_UTILITY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.providers.downloads",
        "com.android.phone",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.daemonapp",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.google.android.packageinstaller"
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
        "downloading",
        "upload complete",
        "battery",
        "charging",
        "usb debugging",
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

    fun isSystemOrUtilityPackage(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        if (pkg.isBlank()) return true
        if (pkg in SYSTEM_UTILITY_PACKAGES) return true
        if (pkg.startsWith("com.example")) return true
        return pkg.contains("systemui") ||
            pkg.contains("launcher") ||
            pkg.contains("keyguard") ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("packageinstaller")
    }

    fun isNoisyOrSystemNotification(
        packageName: String,
        senderName: String,
        body: String
    ): Boolean {
        if (isSystemOrUtilityPackage(packageName)) return true
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

    fun isAllPlatformAutoReplyGoal(goal: String): Boolean {
        val lower = goal.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        return lower.contains("any platform") ||
            lower.contains("all-platform") ||
            lower.contains("all platform") ||
            lower.contains("যেকোনো প্ল্যাটফর্ম") ||
            lower.contains("যেকোনো প্লার্টফর্ম") ||
            lower.contains("যে কোনো প্ল্যাটফর্ম") ||
            lower.contains("যে কোনো প্লার্টফর্ম") ||
            lower.contains("যেকোনো এ্যাপ") ||
            lower.contains("যেকোনো অ্যাপ")
    }

    fun isNotificationDrivenAutoReplyGoal(
        goal: String,
        mode: AgentExecutionMode,
        settings: AgentSettings
    ): Boolean {
        if (CarromContextHelper.isCarromGoalOrApp(goal, "", null)) return false
        if (isAllPlatformAutoReplyGoal(goal)) return true
        if (!settings.replyOnlyOnMessageNotifications) return false

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

    fun appSearchNameForPackage(
        packageName: String,
        allowAnyApp: Boolean = false,
        packageLabelResolver: ((String) -> String?)? = null
    ): String? {
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        if (isSystemOrUtilityPackage(pkg)) return null
        val known = when {
            pkg == "com.whatsapp.w4b" || pkg.contains("w4b") || pkg.contains("whatsapp.business") -> "WA Business"
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg == "com.facebook.orca" || pkg == "com.facebook.mlite" || pkg.contains("messenger") -> "Messenger"
            pkg == "com.facebook.lite" -> "Lite"
            pkg == "com.facebook.katana" -> "Facebook"
            pkg == "com.instagram.barcelona" -> "Threads"
            pkg == "com.instagram.android" -> "Instagram"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("imo") -> "imo"
            pkg.contains("viber") -> "Viber"
            pkg.contains("discord") -> "Discord"
            pkg.contains("snapchat") -> "Snapchat"
            pkg.contains("securesms") -> "Signal"
            pkg.contains("naver.line") -> "LINE"
            pkg.contains("messaging") -> "Messages"
            pkg.contains("tencent.mm") || pkg.contains("wechat") -> "WeChat"
            pkg.contains("twitter") -> "X"
            pkg.contains("linkedin") -> "LinkedIn"
            pkg.contains("skype") -> "Skype"
            pkg.contains("musically") || pkg.contains("ugc.trill") -> "TikTok"
            else -> null
        }
        if (known != null) return known
        if (!allowAnyApp) return null

        val resolved = packageLabelResolver?.invoke(packageName)?.trim()?.takeIf { it.isNotBlank() }
        if (resolved != null) return resolved

        val tail = pkg.substringAfterLast('.').takeIf { it.length >= 2 && it !in setOf("android", "app", "mobile", "client") }
            ?: pkg.split('.').lastOrNull { it.length >= 3 }
            ?: return null
        return tail.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    fun extractNewTargets(
        notifications: List<NotificationSignal>,
        minTimestampMs: Long,
        handledNotificationKeys: Set<String>,
        alreadyQueuedOrActive: List<NotificationReplyTarget>,
        allowAnyApp: Boolean = false,
        packageLabelResolver: ((String) -> String?)? = null
    ): List<NotificationReplyTarget> {
        val results = mutableListOf<NotificationReplyTarget>()
        for (sig in notifications) {
            if (sig.timestampMs < minTimestampMs) continue
            if (!allowAnyApp && !ChatTurnGuard.isMessagingPackage(sig.packageName)) continue
            if (isSystemOrUtilityPackage(sig.packageName)) continue

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

            val appName = appSearchNameForPackage(
                packageName = sig.packageName,
                allowAnyApp = allowAnyApp,
                packageLabelResolver = packageLabelResolver
            ) ?: continue
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
            "RELATIONSHIP CHECK: No relationship saved in app for '$who' -> Keep talking simply, naturally, politely & nicely (সিম্পল, স্বাভাবিক, সুন্দর ও ভদ্রভাবে কথা বলো) according to their message."
        }
    }
}
