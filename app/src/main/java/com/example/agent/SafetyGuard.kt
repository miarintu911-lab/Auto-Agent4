package com.example.agent

object SafetyGuard {

    // Always blocked for user approval: payments, money transfer, final checkout/orders, PIN/OTP
    private val paymentAndFinancialKeywords = listOf(
        "pay", "payment", "pay now", "checkout", "proceed to pay", "purchase",
        "buy now", "place order", "confirm order", "order now", "complete order",
        "transfer", "send money", "cash out", "add money", "recharge", "top up",
        "bkash", "nagad", "rocket", "upay", "upi", "wallet",
        "credit card", "debit card", "card number", "cvv", "billing",
        "otp", "pin", "passcode", "password", "biometric",
        "factory reset", "erase all", "format",
        "পেমেন্ট", "পরিশোধ", "টাকা পাঠান", "অর্ডার কনফার্ম", "অর্ডার করুন", "কিনুন", "বিকাশ", "নগদ"
    )

    // Extra keywords gated only when user opts into STRICT_ALL mode
    private val strictExtraKeywords = listOf(
        "delete", "remove", "uninstall", "send", "submit", "post", "publish",
        "call", "dial", "permission", "allow",
        "ডিলিট", "মুছুন", "পাঠান", "কল"
    )

    data class Evaluation(
        val needsConfirmation: Boolean,
        val reason: String
    )

    fun evaluate(
        action: PlannedAction,
        targetNode: ScreenNode?,
        policyMode: SafetyPolicyMode
    ): Evaluation {
        if (action.actionType in listOf(
                AgentActionType.COMPLETE,
                AgentActionType.FAIL,
                AgentActionType.WAIT,
                AgentActionType.BACK,
                AgentActionType.HOME,
                AgentActionType.SCROLL_DOWN,
                AgentActionType.SCROLL_UP
            )
        ) {
            return Evaluation(needsConfirmation = false, reason = "")
        }

        val combinedText = buildString {
            append(action.reason.lowercase())
            append(' ')
            append(action.textToType?.lowercase().orEmpty())
            if (targetNode != null) {
                append(' ')
                append(targetNode.text.lowercase())
                append(' ')
                append(targetNode.contentDesc.lowercase())
                append(' ')
                append(targetNode.viewId.lowercase())
            }
        }

        val matchedPayment = paymentAndFinancialKeywords.firstOrNull { keyword ->
            containsTokenOrPhrase(combinedText, keyword)
        }
        if (matchedPayment != null) {
            return Evaluation(
                needsConfirmation = true,
                reason = "Payment/Security Guard triggered ('$matchedPayment'). Approve before continuing."
            )
        }

        if (action.requiresConfirmation) {
            return Evaluation(
                needsConfirmation = true,
                reason = "AI flagged payment/security sensitivity: ${action.reason}"
            )
        }

        if (policyMode == SafetyPolicyMode.STRICT_ALL) {
            val matchedStrict = strictExtraKeywords.firstOrNull { keyword ->
                containsTokenOrPhrase(combinedText, keyword)
            }
            if (matchedStrict != null) {
                return Evaluation(
                    needsConfirmation = true,
                    reason = "Strict Guard triggered ('$matchedStrict') on target control."
                )
            }
        }

        return Evaluation(needsConfirmation = false, reason = "")
    }

    fun isStuckLoop(
        recentLogs: List<AgentStepLog>,
        candidate: PlannedAction,
        targetSummary: String
    ): Boolean {
        if (candidate.actionType == AgentActionType.WAIT) return false
        if (recentLogs.size < 3) return false
        val lastThree = recentLogs.takeLast(3)
        return lastThree.all {
            it.actionType == candidate.actionType &&
                it.targetSummary == targetSummary &&
                it.status == StepStatus.EXECUTED
        }
    }

    private fun containsTokenOrPhrase(haystack: String, needle: String): Boolean {
        if (needle.contains(' ') || needle.any { it.code > 127 }) {
            return haystack.contains(needle)
        }
        val regex = Regex("\\b${Regex.escape(needle)}\\b")
        return regex.containsMatchIn(haystack)
    }
}
