package com.example.agent

data class LauncherSearchDecision(
    val action: PlannedAction,
    val targetAppName: String?,
    val stageTag: String
)

/**
 * Deterministic App-Drawer Search navigator (Samsung One UI Home & standard Android launchers).
 *
 * Ensures that before working inside a requested app, the agent:
 * 1. Opens the App Drawer page (like One UI Apps screen with bottom "Search" pill),
 * 2. Taps the Search bar,
 * 3. Inputs the exact target app name,
 * 4. Taps the matching search result to enter the app.
 */
object LauncherSearchHelper {

    private val appAliases: List<Pair<List<String>, String>> = listOf(
        listOf("facebook lite", "fb lite", "ফেসবুক লাইট") to "Lite",
        listOf("wa business", "whatsapp business", "wa4b", "ডব্লিউএ বিজনেস", "হোয়াটসঅ্যাপ বিজনেস", "হোয়াটসঅ্যাপ বিজনেস") to "WA Business",
        listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ") to "WhatsApp",
        listOf("messenger", "মেসেঞ্জার") to "Messenger",
        listOf("facebook", "ফেসবুক") to "Facebook",
        listOf("youtube", "ইউটিউব") to "YouTube",
        listOf("nagad", "নগদ") to "Nagad",
        listOf("bkash", "বিকাশ") to "bKash",
        listOf("rocket", "রকেট") to "Rocket",
        listOf("imo", "ইমো") to "imo",
        listOf("gemini", "জেমিনি") to "Gemini",
        listOf("telegram", "টেলিগ্রাম") to "Telegram",
        listOf("instagram", "ইনস্টাগ্রাম") to "Instagram",
        listOf("chrome", "ক্রোম") to "Chrome",
        listOf("vlc") to "VLC",
        listOf("1dm") to "1DM",
        listOf("termux") to "Termux",
        listOf("ridmik", "রিদ্মিক") to "Ridmik",
        listOf("my galaxy") to "My Galaxy",
        listOf("sahaj mobile", "সহজ মোবাইল") to "Sahaj Mobile",
        listOf("mycirkle", "cirkle", "airtel") to "MyCirkle-Airtel",
        listOf("1.1.1.1") to "1.1.1.1",
        listOf("carrom", "ক্যারম") to "Carrom",
        listOf("play store", "প্লে স্টোর") to "Play Store",
        listOf("settings", "সেটিংস") to "Settings",
        listOf("gmail", "জিমেইল") to "Gmail",
        listOf("maps", "ম্যাপস") to "Maps",
        listOf("tiktok", "টিকটক") to "TikTok",
        listOf("lite") to "Lite"
    )

    private val expectedPackageHints: Map<String, List<String>> = mapOf(
        "wa business" to listOf("com.whatsapp.w4b", "w4b"),
        "whatsapp" to listOf("whatsapp"),
        "lite" to listOf("facebook.lite", "lite"),
        "facebook" to listOf("facebook.katana", "facebook.lite", "facebook"),
        "messenger" to listOf("facebook.orca", "facebook.mlite", "messenger"),
        "youtube" to listOf("youtube"),
        "nagad" to listOf("nagad"),
        "bkash" to listOf("bkash"),
        "imo" to listOf("imo"),
        "gemini" to listOf("bard", "googlequicksearchbox", "gemini"),
        "telegram" to listOf("telegram"),
        "instagram" to listOf("instagram"),
        "chrome" to listOf("chrome"),
        "vlc" to listOf("vlc"),
        "1dm" to listOf("idm", "1dm"),
        "termux" to listOf("termux"),
        "ridmik" to listOf("ridmik"),
        "carrom" to listOf("carrom"),
        "play store" to listOf("vending"),
        "settings" to listOf("settings"),
        "gmail" to listOf("gm", "gmail"),
        "maps" to listOf("maps")
    )

    fun isLauncherOrSearchScreen(packageName: String, nodes: List<ScreenNode>): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.contains("launcher") ||
            pkg.contains("oneui") ||
            pkg.contains("galaxyfinder") ||
            pkg.contains("quicksearchbox") ||
            pkg.contains("trebuchet") ||
            pkg.contains("miui.home") ||
            pkg.contains("lawnchair") ||
            pkg.contains("nova")
        ) {
            return true
        }
        return false
    }

    fun extractTargetAppName(goal: String): String? {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()

        for ((keys, canonical) in appAliases) {
            for (k in keys) {
                if (k.any { it.code > 127 } || k.contains(' ') || k.contains('.')) {
                    if (lower.contains(k)) return canonical
                } else {
                    val regex = Regex("\\b${Regex.escape(k)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(lower)) return canonical
                }
            }
        }

        val englishVerbRegex = Regex(
            "(?:open|launch|start|use|inside|in)\\s+(?:the\\s+)?([A-Za-z0-9][A-Za-z0-9._-]{1,24})(?:\\s+app)?",
            RegexOption.IGNORE_CASE
        )
        val genericStop = setOf(
            "the", "an", "a", "app", "page", "screen", "home", "this", "that", "my", "your", "search"
        )
        englishVerbRegex.findAll(trimmed).forEach { m ->
            val candidate = m.groupValues[1].trim()
            if (candidate.lowercase() !in genericStop) {
                return candidate
            }
        }

        val bengaliRegex = Regex(
            "([A-Za-z0-9][A-Za-z0-9._-]{1,24})\\s*(?:এ্যাপ|অ্যাপ|এ\\s*যাও|এ\\s*গিয়ে|এর\\s*ভিতরে|ওপেন|খুল)"
        )
        bengaliRegex.find(trimmed)?.let { m ->
            val candidate = m.groupValues[1].trim()
            if (candidate.lowercase() !in genericStop) {
                return candidate
            }
        }

        return null
    }

    fun findEditableSearchNode(nodes: List<ScreenNode>): ScreenNode? {
        val editable = nodes.filter { it.isEditable }
        if (editable.isEmpty()) return null
        return editable.firstOrNull { node ->
            val id = node.viewId.lowercase()
            val lbl = node.displayLabel().lowercase()
            id.contains("search") || id.contains("query") || id.contains("finder") ||
                lbl.contains("search") || lbl.contains("find") || lbl.contains("খুঁজ")
        } ?: editable.first()
    }

    fun findSearchBarTriggerNode(nodes: List<ScreenNode>): ScreenNode? {
        val candidates = nodes.filter { it.isClickable || it.isEditable }
        return candidates.firstOrNull { node ->
            val id = node.viewId.substringAfterLast('/').lowercase()
            val text = node.text.trim().lowercase()
            val desc = node.contentDesc.trim().lowercase()
            id.contains("search") ||
                id.contains("finder") ||
                text == "search" ||
                text.startsWith("search ") ||
                desc == "search" ||
                desc.startsWith("search ") ||
                text.contains("খুঁজুন")
        }
    }

    fun findMatchingAppResultNode(
        targetAppName: String,
        nodes: List<ScreenNode>,
        searchEditNode: ScreenNode?
    ): ScreenNode? {
        val targetLower = targetAppName.trim().lowercase()
        if (targetLower.isBlank()) return null

        val candidates = nodes.filter { node ->
            if (node.id == searchEditNode?.id || node.isEditable) return@filter false
            val lbl = node.displayLabel().trim()
            val lblLower = lbl.lowercase()
            if (lbl.isBlank()) return@filter false
            if (lblLower.startsWith("search ") || lblLower.contains("search for ") || lblLower == "search") {
                return@filter false
            }
            true
        }

        // 1. Exact label match on a clickable app icon/container
        candidates.firstOrNull {
            it.isClickable && (it.text.trim().equals(targetLower, ignoreCase = true) ||
                it.contentDesc.trim().equals(targetLower, ignoreCase = true))
        }?.let { return it }

        // 2. Exact label match even if clickable wrapper is parent (still tap coordinates cleanly)
        candidates.firstOrNull {
            it.text.trim().equals(targetLower, ignoreCase = true) ||
                it.contentDesc.trim().equals(targetLower, ignoreCase = true)
        }?.let { return it }

        // 3. StartsWith / word match on clickable nodes (avoid matching inside long hints)
        candidates.firstOrNull {
            val lbl = it.displayLabel().trim().lowercase()
            it.isClickable && lbl.length <= targetLower.length + 14 &&
                (lbl.startsWith(targetLower) || lbl.contains(targetLower) ||
                    (targetLower == "wa business" && lbl.contains("whatsapp business")))
        }?.let { return it }

        return candidates.firstOrNull {
            val lbl = it.displayLabel().trim().lowercase()
            lbl.length <= targetLower.length + 14 &&
                (lbl.startsWith(targetLower) || lbl.contains(targetLower) ||
                    (targetLower == "wa business" && lbl.contains("whatsapp business")))
        }
    }

    fun isLikelyAppDrawerGrid(snapshot: UiSnapshot): Boolean {
        val nodes = snapshot.nodes
        val hasMoreOptions = nodes.any {
            it.contentDesc.contains("More options", ignoreCase = true) ||
                it.viewId.contains("more", ignoreCase = true) ||
                it.viewId.contains("apps_", ignoreCase = true)
        }
        val hasSearchBar = findSearchBarTriggerNode(nodes) != null || findEditableSearchNode(nodes) != null
        val clickableAppIcons = nodes.count {
            it.isClickable && it.bounds.bottom < (snapshot.screenHeight * 0.88f).toInt()
        }
        return hasSearchBar || (hasMoreOptions && clickableAppIcons >= 5)
    }

    fun planNextLauncherSearchStep(
        goal: String,
        snapshot: UiSnapshot,
        alreadyOpenedDrawer: Boolean,
        alreadyTappedSearch: Boolean,
        alreadyTypedSearch: Boolean,
        targetAppOverride: String? = null
    ): LauncherSearchDecision? {
        if (!isLauncherOrSearchScreen(snapshot.packageName, snapshot.nodes)) return null
        val targetAppName = targetAppOverride?.takeIf { it.isNotBlank() } ?: extractTargetAppName(goal)

        // Step A: Editable Search field is currently visible (e.g., Finder / App Drawer Search)
        val editNode = findEditableSearchNode(snapshot.nodes)
        if (editNode != null) {
            val currentQuery = editNode.text.trim()
            if (targetAppName != null) {
                val queryAlreadyTyped = currentQuery.equals(targetAppName, ignoreCase = true) ||
                    (alreadyTypedSearch && currentQuery.contains(targetAppName, ignoreCase = true))

                if (queryAlreadyTyped) {
                    val resultNode = findMatchingAppResultNode(targetAppName, snapshot.nodes, editNode)
                    if (resultNode != null) {
                        return LauncherSearchDecision(
                            action = PlannedAction(
                                actionType = AgentActionType.TAP,
                                targetNodeId = resultNode.id,
                                x = resultNode.bounds.centerX,
                                y = resultNode.bounds.centerY,
                                textToType = null,
                                waitSeconds = null,
                                memoryNote = "Found '$targetAppName' in App Drawer Search; opening app.",
                                reason = "Tap search result '$targetAppName' to open app",
                                requiresConfirmation = false
                            ),
                            targetAppName = targetAppName,
                            stageTag = "TAP_APP_RESULT"
                        )
                    }
                } else {
                    return LauncherSearchDecision(
                        action = PlannedAction(
                            actionType = AgentActionType.INPUT_TEXT,
                            targetNodeId = editNode.id,
                            x = editNode.bounds.centerX,
                            y = editNode.bounds.centerY,
                            textToType = targetAppName,
                            waitSeconds = null,
                            memoryNote = "Typing exact app name '$targetAppName' in App Drawer Search.",
                            reason = "Search exact app name '$targetAppName'",
                            requiresConfirmation = false
                        ),
                        targetAppName = targetAppName,
                        stageTag = "TYPE_APP_NAME"
                    )
                }
            }
            return null
        }

        // Step B: Search bar pill/button is visible on the App Drawer screen (exact screenshot page!)
        val searchTrigger = findSearchBarTriggerNode(snapshot.nodes)
        if (searchTrigger != null && !alreadyTappedSearch) {
            return LauncherSearchDecision(
                action = PlannedAction(
                    actionType = AgentActionType.TAP,
                    targetNodeId = searchTrigger.id,
                    x = searchTrigger.bounds.centerX,
                    y = searchTrigger.bounds.centerY,
                    textToType = null,
                    waitSeconds = null,
                    memoryNote = "Opening App Drawer Search bar to search ${targetAppName ?: "target app"}.",
                    reason = "Tap App Drawer Search bar",
                    requiresConfirmation = false
                ),
                targetAppName = targetAppName,
                stageTag = "TAP_SEARCH_BAR"
            )
        }

        // Step C: We appear to be in Samsung One UI App Drawer, but Search pill didn't expose accessibility text
        if (isLikelyAppDrawerGrid(snapshot) && !alreadyTappedSearch) {
            val tapX = (snapshot.screenWidth * 0.46f).toInt()
            val tapY = (snapshot.screenHeight * 0.935f).toInt()
            return LauncherSearchDecision(
                action = PlannedAction(
                    actionType = AgentActionType.TAP,
                    targetNodeId = null,
                    x = tapX,
                    y = tapY,
                    textToType = null,
                    waitSeconds = null,
                    memoryNote = "Tapping bottom One UI App Drawer Search pill.",
                    reason = "Tap bottom Search bar in App Drawer",
                    requiresConfirmation = false
                ),
                targetAppName = targetAppName,
                stageTag = "TAP_SEARCH_PILL_FALLBACK"
            )
        }

        // Step D: Still on Home workspace screen -> swipe up to open the App Drawer Search page
        if (!alreadyOpenedDrawer) {
            val cx = (snapshot.screenWidth * 0.5f).toInt()
            val startY = (snapshot.screenHeight * 0.74f).toInt()
            val endY = (snapshot.screenHeight * 0.24f).toInt()
            return LauncherSearchDecision(
                action = PlannedAction(
                    actionType = AgentActionType.DRAG,
                    targetNodeId = null,
                    x = cx,
                    y = startY,
                    endX = cx,
                    endY = endY,
                    durationMs = 300L,
                    textToType = null,
                    waitSeconds = null,
                    memoryNote = "Swiping up to open App Drawer Search page.",
                    reason = "Swipe up from Home to open App Drawer",
                    requiresConfirmation = false
                ),
                targetAppName = targetAppName,
                stageTag = "OPEN_APP_DRAWER"
            )
        }

        return null
    }

    fun didLandInWrongApp(
        goal: String,
        currentPackage: String,
        selfPackage: String,
        expectedAppOverride: String? = null
    ): Boolean {
        if (currentPackage.isBlank() || currentPackage == "unknown" || currentPackage == selfPackage) {
            return false
        }
        val pkgLower = currentPackage.lowercase()
        if (isLauncherOrSearchScreen(pkgLower, emptyList()) || pkgLower == "com.android.systemui") {
            return false
        }
        val targetApp = (expectedAppOverride?.takeIf { it.isNotBlank() } ?: extractTargetAppName(goal))
            ?.lowercase()
            ?: return false
        val hints = expectedPackageHints[targetApp] ?: listOf(targetApp.replace(" ", ""))
        if (hints.any { pkgLower.contains(it) }) {
            return false
        }
        // Only flag if currentPackage clearly belongs to a different known app
        val isDifferentKnownApp = expectedPackageHints.entries.any { (appKey, appHints) ->
            appKey != targetApp && appHints.any { pkgLower.contains(it) }
        }
        return isDifferentKnownApp
    }
}
