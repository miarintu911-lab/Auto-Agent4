package com.example.agent

/**
 * Deterministic Mobile-Agent-v3 style Action Reflector & Manager Checklist gate.
 * Compares pre-action expectations against the post-action UI snapshot so silent tap
 * misses, rejected text inputs, and premature task completions are caught immediately.
 */
object ActionReflectorHelper {

    fun hasIncompleteChecklist(checklist: String?): Boolean {
        return !checklist.isNullOrBlank() && checklist.contains("[ ]")
    }

    fun buildReflectionHint(
        prevActionType: AgentActionType?,
        prevPackageName: String?,
        prevInputText: String?,
        consecutiveNoChangeCount: Int,
        snapshot: UiSnapshot,
        isCarromContext: Boolean
    ): String {
        if (prevActionType == null || prevActionType == AgentActionType.WAIT) {
            return if (prevPackageName != null && prevPackageName != snapshot.packageName) {
                "[REFLECTOR: Screen switched from $prevPackageName to ${snapshot.packageName}.]"
            } else {
                ""
            }
        }

        val warnings = mutableListOf<String>()

        if (!isCarromContext && consecutiveNoChangeCount > 0) {
            warnings.add(
                "[REFLECTOR WARNING: Screen UI did NOT change (${consecutiveNoChangeCount}x) after ${prevActionType.name} — tap the Set-of-Mark numbered box, parent container, scroll, or press BACK instead of repeating!]"
            )
        }

        if (prevActionType == AgentActionType.INPUT_TEXT && !prevInputText.isNullOrBlank()) {
            val probe = prevInputText.trim().take(18).lowercase()
            val textObserved = snapshot.nodes.any { node ->
                val t = node.text.trim().lowercase()
                val d = node.contentDesc.trim().lowercase()
                (t.isNotBlank() && t.contains(probe)) || (d.isNotBlank() && d.contains(probe))
            }
            val hasPasswordField = snapshot.nodes.any {
                it.isEditable && (it.viewId.contains("password", ignoreCase = true) ||
                    it.text.all { ch -> ch == '•' || ch == '*' })
            }
            if (!textObserved && !hasPasswordField) {
                warnings.add(
                    "[REFLECTOR WARNING: Typed text '${prevInputText.take(28)}' is NOT visible in the UI tree after INPUT_TEXT — focus/tap the green editable box first and re-enter text before tapping Send/Search!]"
                )
            }
        }

        if (warnings.isNotEmpty()) {
            return warnings.joinToString(" ")
        }

        return if (prevPackageName != null && prevPackageName != snapshot.packageName) {
            "[REFLECTOR OK: Transitioned cleanly from $prevPackageName to ${snapshot.packageName} after ${prevActionType.name}.]"
        } else {
            "[REFLECTOR OK: UI updated after ${prevActionType.name}.]"
        }
    }
}
