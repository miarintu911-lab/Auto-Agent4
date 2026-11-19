package com.example.ai

import com.example.agent.AgentSettings
import com.example.agent.AiProvider

data class BulkKeyImportSummary(
    val googleCount: Int,
    val groqCount: Int,
    val openRouterCount: Int,
    val newlyAddedCount: Int
) {
    val totalImported: Int get() = googleCount + groqCount + openRouterCount
}

object BulkKeyImporter {

    private val tokenRegex = Regex("""[A-Za-z0-9_\-\.]{16,}""")
    private val stripSurroundingQuotesRegex = Regex("""^["'`]+|["'`,;]+$""")

    /**
     * Parses raw text from .txt, .csv, .json, .env, or mixed clipboard paste.
     * Automatically classifies keys by standard provider prefixes:
     * - Google AI Studio: "AIza..."
     * - Groq Cloud: "gsk_..."
     * - OpenRouter: "sk-or-..."
     * Unrecognized key tokens are assigned to [fallbackProvider] if it requires a key.
     */
    fun importIntoSettings(
        rawContent: String,
        currentSettings: AgentSettings,
        fallbackProvider: AiProvider
    ): Pair<AgentSettings, BulkKeyImportSummary> {
        val googleKeys = LinkedHashSet(currentSettings.apiKeysFor(AiProvider.GOOGLE_AI_STUDIO))
        val groqKeys = LinkedHashSet(currentSettings.apiKeysFor(AiProvider.GROQ_CLOUD))
        val openRouterKeys = LinkedHashSet(currentSettings.apiKeysFor(AiProvider.OPENROUTER))

        var matchedGoogle = 0
        var matchedGroq = 0
        var matchedOpenRouter = 0
        var newlyAdded = 0

        val candidateTokens = extractCandidateKeys(rawContent)
        for (token in candidateTokens) {
            when (classifyKeyProvider(token, fallbackProvider)) {
                AiProvider.GOOGLE_AI_STUDIO -> {
                    matchedGoogle++
                    if (googleKeys.add(token)) newlyAdded++
                }
                AiProvider.GROQ_CLOUD -> {
                    matchedGroq++
                    if (groqKeys.add(token)) newlyAdded++
                }
                AiProvider.OPENROUTER -> {
                    matchedOpenRouter++
                    if (openRouterKeys.add(token)) newlyAdded++
                }
                AiProvider.FIREBASE_AI -> Unit
            }
        }

        val updated = currentSettings.copy(
            googleAiStudioApiKey = googleKeys.joinToString("\n"),
            groqApiKey = groqKeys.joinToString("\n"),
            openRouterApiKey = openRouterKeys.joinToString("\n")
        )
        val summary = BulkKeyImportSummary(
            googleCount = matchedGoogle,
            groqCount = matchedGroq,
            openRouterCount = matchedOpenRouter,
            newlyAddedCount = newlyAdded
        )
        return updated to summary
    }

    /**
     * Sorts mixed pasted text from [sourceProvider]'s box into Google AI Studio,
     * Groq Cloud, and OpenRouter, keeping only keys belonging to [sourceProvider] in its box.
     */
    fun redistributeMixedBlock(
        mixedText: String,
        currentSettings: AgentSettings,
        sourceProvider: AiProvider
    ): Pair<AgentSettings, BulkKeyImportSummary> {
        val candidateTokens = extractCandidateKeys(mixedText)
        val sourceRemaining = candidateTokens
            .filter { classifyKeyProvider(it, sourceProvider) == sourceProvider }
            .distinct()
            .joinToString("\n")
        val sanitizedCurrent = when (sourceProvider) {
            AiProvider.GOOGLE_AI_STUDIO -> currentSettings.copy(googleAiStudioApiKey = sourceRemaining)
            AiProvider.GROQ_CLOUD -> currentSettings.copy(groqApiKey = sourceRemaining)
            AiProvider.OPENROUTER -> currentSettings.copy(openRouterApiKey = sourceRemaining)
            AiProvider.FIREBASE_AI -> currentSettings
        }
        return importIntoSettings(mixedText, sanitizedCurrent, sourceProvider)
    }

    fun classifyKeyProvider(key: String, fallbackProvider: AiProvider): AiProvider {
        return when {
            key.startsWith("AIza") -> AiProvider.GOOGLE_AI_STUDIO
            key.startsWith("gsk_") -> AiProvider.GROQ_CLOUD
            key.startsWith("sk-or-") -> AiProvider.OPENROUTER
            fallbackProvider.requiresApiKey -> fallbackProvider
            else -> AiProvider.GOOGLE_AI_STUDIO
        }
    }

    internal fun extractCandidateKeys(rawContent: String): List<String> {
        if (rawContent.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val lines = rawContent.lines()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            // Split common delimiters (JSON quotes/colons/brackets, CSV commas, env '=', whitespace)
            val roughParts = line.split(Regex("""[\s,;="\[\]{}:]+"""))
            for (part in roughParts) {
                val cleaned = part.replace(stripSurroundingQuotesRegex, "").trim()
                if (cleaned.length < 16) continue
                if (looksLikePlaceholderOrWord(cleaned)) continue
                for (match in tokenRegex.findAll(cleaned)) {
                    val token = match.value
                    if (!looksLikePlaceholderOrWord(token)) {
                        seen.add(token)
                    }
                }
            }
        }
        return seen.toList()
    }

    private fun looksLikePlaceholderOrWord(token: String): Boolean {
        val lower = token.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower in setOf(
                "google_ai_studio",
                "googleaistudio",
                "groq_cloud",
                "openrouter",
                "api_keys",
                "your_api_key_here",
                "replace_with_key"
            )
        ) {
            return true
        }
        // Known prefixes are always real keys
        if (token.startsWith("AIza") || token.startsWith("gsk_") || token.startsWith("sk-or-")) {
            return false
        }
        // Generic keys must contain at least one digit or mix of uppercase/lowercase/underscore/dash
        val hasDigit = token.any { it.isDigit() }
        val hasUpperAndLower = token.any { it.isUpperCase() } && token.any { it.isLowerCase() }
        return !(hasDigit || hasUpperAndLower)
    }
}
