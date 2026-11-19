package com.example.ai

import com.example.agent.AgentSettings
import com.example.agent.AiProvider
import com.example.agent.AiRoutingStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class AiTaskProfile {
    FAST_UI,
    BALANCED,
    HIGH_ACCURACY
}

data class KeyAttemptResult<T>(
    val value: T,
    val providerUsed: AiProvider,
    val modelUsed: String,
    val keyIndexOneBased: Int,
    val totalKeysInProvider: Int,
    val failoverCount: Int,
    val wasHedged: Boolean = false
)

object ApiKeyFailoverManager {

    internal const val RATE_LIMIT_COOLDOWN_MS = 65_000L
    internal const val DEFAULT_HEDGE_DELAY_MS = 450L

    private val activeIndexByProvider = ConcurrentHashMap<AiProvider, Int>()
    private val permanentFailedKeysByProvider = ConcurrentHashMap<AiProvider, MutableSet<String>>()
    private val cooldownUntilByProviderKey = ConcurrentHashMap<String, Long>()
    private val lastRouteSummary = ConcurrentHashMap<String, String>()
    private val roundRobinTurn = AtomicInteger(0)

    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }
    internal var hedgeDelayMs: Long = DEFAULT_HEDGE_DELAY_MS

    fun getLastRouteLabel(): String = lastRouteSummary["route"].orEmpty()

    fun getActiveKeyPosition(provider: AiProvider, totalKeys: Int): Int {
        if (totalKeys <= 0) return 0
        return (activeIndexByProvider[provider] ?: 0).coerceIn(0, totalKeys - 1)
    }

    fun getFailedCount(provider: AiProvider): Int {
        return permanentFailedKeysByProvider[provider]?.size ?: 0
    }

    fun getCooldownCount(provider: AiProvider): Int {
        val now = nowMsProvider()
        val prefix = "${provider.name}:"
        var count = 0
        cooldownUntilByProviderKey.entries.forEach { (composite, expiresAt) ->
            if (composite.startsWith(prefix)) {
                if (expiresAt > now) {
                    count++
                } else {
                    cooldownUntilByProviderKey.remove(composite, expiresAt)
                }
            }
        }
        return count
    }

    fun resetHealth() {
        activeIndexByProvider.clear()
        permanentFailedKeysByProvider.clear()
        cooldownUntilByProviderKey.clear()
        lastRouteSummary.clear()
        roundRobinTurn.set(0)
        nowMsProvider = { System.currentTimeMillis() }
        hedgeDelayMs = DEFAULT_HEDGE_DELAY_MS
    }

    fun orderedProviders(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile = AiTaskProfile.BALANCED
    ): List<AiProvider> {
        val configured = mutableListOf<AiProvider>()
        val baseOrder = when (taskProfile) {
            AiTaskProfile.FAST_UI -> listOf(
                AiProvider.GROQ_CLOUD,
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.OPENROUTER,
                AiProvider.FIREBASE_AI
            )
            AiTaskProfile.HIGH_ACCURACY -> listOf(
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.OPENROUTER,
                AiProvider.GROQ_CLOUD,
                AiProvider.FIREBASE_AI
            )
            AiTaskProfile.BALANCED -> listOf(
                settings.aiProvider,
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.GROQ_CLOUD,
                AiProvider.OPENROUTER,
                AiProvider.FIREBASE_AI
            ).distinct()
        }

        if (!settings.autoCrossProviderFailover) {
            return listOf(settings.aiProvider)
        }

        for (p in baseOrder) {
            if (p == AiProvider.FIREBASE_AI && !firebaseConfigured) continue
            if (p.requiresApiKey && settings.apiKeysFor(p).isEmpty()) continue
            configured.add(p)
        }

        if (configured.isEmpty()) {
            return listOf(settings.aiProvider)
        }

        if (settings.routingStrategy == AiRoutingStrategy.STICKY_FAILOVER) {
            val stickyList = mutableListOf(settings.aiProvider)
            configured.filterTo(stickyList) { it != settings.aiProvider }
            return stickyList
        }

        // SMART_HYBRID: Rotate across configured providers on each call (Round-Robin)
        // so 1 call goes to Groq, next to Google AI Studio, next to OpenRouter, etc.
        val turn = roundRobinTurn.getAndIncrement()
        val shift = if (configured.size > 1) turn % configured.size else 0
        return (0 until configured.size).map { offset ->
            configured[(shift + offset) % configured.size]
        }
    }

    fun orderedKeyIndices(provider: AiProvider, keys: List<String>): List<Int> {
        if (keys.isEmpty()) return emptyList()
        val now = nowMsProvider()
        val start = getActiveKeyPosition(provider, keys.size)
        val permanentFailed = permanentFailedKeysByProvider[provider].orEmpty()

        val rotated = (0 until keys.size).map { offset -> (start + offset) % keys.size }
        val healthy = mutableListOf<Int>()
        val coolingDown = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        for (idx in rotated) {
            val key = keys[idx]
            val composite = "${provider.name}:$key"
            val cooldownUntil = cooldownUntilByProviderKey[composite]
            if (cooldownUntil != null && cooldownUntil <= now) {
                cooldownUntilByProviderKey.remove(composite, cooldownUntil)
            }
            when {
                key in permanentFailed -> failed.add(idx)
                cooldownUntil != null && cooldownUntil > now -> coolingDown.add(idx)
                else -> healthy.add(idx)
            }
        }

        // Priority: Ready healthy keys -> cooling-down keys -> permanent failed as last resort
        return healthy + coolingDown + failed
    }

    private data class ExecutionSlot(
        val provider: AiProvider,
        val apiKey: String,
        val keyIndexZeroBased: Int,
        val totalKeys: Int,
        val primaryModel: String,
        val fallbackModel: String?
    )

    private fun pickDualHedgeSlots(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile
    ): Pair<ExecutionSlot, ExecutionSlot>? {
        val providers = orderedProviders(settings, firebaseConfigured, taskProfile)
        val primaryCandidates = mutableListOf<ExecutionSlot>()

        for (provider in providers) {
            if (!provider.requiresApiKey) {
                if (firebaseConfigured) {
                    primaryCandidates.add(
                        ExecutionSlot(
                            provider = provider,
                            apiKey = "",
                            keyIndexZeroBased = 0,
                            totalKeys = 1,
                            primaryModel = settings.modelNameFor(provider),
                            fallbackModel = null
                        )
                    )
                }
                continue
            }
            val keys = settings.apiKeysFor(provider)
            if (keys.isEmpty()) continue
            val indices = orderedKeyIndices(provider, keys)
            val model = settings.modelNameFor(provider)
            val fallback = provider.defaultModel.takeIf { it != model }
            indices.take(2).forEach { idx ->
                primaryCandidates.add(
                    ExecutionSlot(
                        provider = provider,
                        apiKey = keys[idx],
                        keyIndexZeroBased = idx,
                        totalKeys = keys.size,
                        primaryModel = model,
                        fallbackModel = fallback
                    )
                )
            }
        }

        if (primaryCandidates.size < 2) return null
        val slotA = primaryCandidates[0]
        // Prefer a second slot from a DIFFERENT provider (e.g. Gemini + Groq) for maximum diversity,
        // otherwise pick the second key of the same provider.
        val slotB = primaryCandidates.firstOrNull {
            it.provider != slotA.provider
        } ?: primaryCandidates.firstOrNull {
            it.provider == slotA.provider && it.apiKey != slotA.apiKey
        } ?: return null

        return slotA to slotB
    }

    /**
     * Runs a 450ms Smart Hedge dual-key race for fast game/vision decisions:
     * - Starts Slot A immediately.
     * - If Slot A replies in <450ms, Slot B never runs (uses only 1 API call).
     * - If Slot A takes >450ms or fails early, Slot B runs in parallel and the first valid answer wins.
     */
    suspend fun <T> executeWithHedgeOrFailover(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile = AiTaskProfile.HIGH_ACCURACY,
        enableHedgeRace: Boolean = settings.turboDualKeyGameMode,
        invokeForProvider: suspend (provider: AiProvider, apiKey: String, model: String) -> T
    ): KeyAttemptResult<T> {
        if (!enableHedgeRace) {
            return executeWithFailover(settings, firebaseConfigured, taskProfile, invokeForProvider)
        }

        val pair = pickDualHedgeSlots(settings, firebaseConfigured, taskProfile)
            ?: return executeWithFailover(settings, firebaseConfigured, taskProfile, invokeForProvider)

        val (slotA, slotB) = pair
        val isRoundRobin = settings.routingStrategy == AiRoutingStrategy.SMART_HYBRID
        val parentJob = currentCoroutineContext()[Job]
        val raceSupervisor = SupervisorJob(parentJob)
        val raceScope = CoroutineScope(Dispatchers.IO + raceSupervisor)

        suspend fun runSlot(slot: ExecutionSlot): Result<KeyAttemptResult<T>> {
            return try {
                var usedModel = slot.primaryModel
                val value = try {
                    invokeForProvider(slot.provider, slot.apiKey, slot.primaryModel)
                } catch (firstErr: Exception) {
                    if (firstErr is CancellationException) throw firstErr
                    val msg = firstErr.message.orEmpty()
                    val isModelNotFound = msg.contains("HTTP 404") ||
                        msg.contains("not found", ignoreCase = true) ||
                        msg.contains("is not supported", ignoreCase = true)
                    if (isModelNotFound && slot.fallbackModel != null) {
                        usedModel = slot.fallbackModel
                        invokeForProvider(slot.provider, slot.apiKey, slot.fallbackModel)
                    } else {
                        throw firstErr
                    }
                }

                if (slot.provider.requiresApiKey) {
                    permanentFailedKeysByProvider[slot.provider]?.remove(slot.apiKey)
                    cooldownUntilByProviderKey.remove("${slot.provider.name}:${slot.apiKey}")
                    activeIndexByProvider[slot.provider] = if (isRoundRobin) {
                        (slot.keyIndexZeroBased + 1) % slot.totalKeys
                    } else {
                        slot.keyIndexZeroBased
                    }
                }
                Result.success(
                    KeyAttemptResult(
                        value = value,
                        providerUsed = slot.provider,
                        modelUsed = usedModel,
                        keyIndexOneBased = slot.keyIndexZeroBased + 1,
                        totalKeysInProvider = slot.totalKeys,
                        failoverCount = 0,
                        wasHedged = true
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (slot.provider.requiresApiKey) {
                    recordKeyFailure(slot.provider, slot.apiKey, slot.keyIndexZeroBased, slot.totalKeys, e)
                }
                Result.failure(e)
            }
        }

        val winner: KeyAttemptResult<T>? = try {
            val earlyFailTrigger = CompletableDeferred<Unit>()
            val secondaryStarted = java.util.concurrent.atomic.AtomicBoolean(false)

            val jobA = raceScope.async {
                val res = runSlot(slotA)
                if (res.isFailure) earlyFailTrigger.complete(Unit)
                res
            }

            val jobB = raceScope.async {
                withTimeoutOrNull(hedgeDelayMs) {
                    earlyFailTrigger.await()
                }
                secondaryStarted.set(true)
                runSlot(slotB)
            }

            select<KeyAttemptResult<T>?> {
                jobA.onAwait { resA ->
                    val hit = resA.getOrNull()
                    if (hit != null) {
                        jobB.cancel()
                        hit.copy(wasHedged = secondaryStarted.get())
                    } else {
                        val resB = jobB.await()
                        resB.getOrNull()?.copy(failoverCount = 1, wasHedged = true)
                    }
                }
                jobB.onAwait { resB ->
                    val hit = resB.getOrNull()
                    if (hit != null) {
                        jobA.cancel()
                        hit.copy(wasHedged = true)
                    } else {
                        val resA = jobA.await()
                        resA.getOrNull()?.copy(failoverCount = 1, wasHedged = true)
                    }
                }
            }
        } finally {
            raceSupervisor.cancel()
        }

        if (winner != null) {
            val prefix = if (winner.wasHedged) "Turbo Hedge " else ""
            lastRouteSummary["route"] = "$prefix${winner.providerUsed.shortBadge} #${winner.keyIndexOneBased}/${winner.totalKeysInProvider}"
            return winner
        }

        // Both hedge slots failed -> fall back across remaining pool
        return executeWithFailover(settings, firebaseConfigured, taskProfile, invokeForProvider)
    }

    suspend fun <T> executeWithFailover(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile = AiTaskProfile.BALANCED,
        invokeForProvider: suspend (provider: AiProvider, apiKey: String, model: String) -> T
    ): KeyAttemptResult<T> {
        val providers = orderedProviders(settings, firebaseConfigured, taskProfile)
        val errors = mutableListOf<String>()
        var failovers = 0
        val isRoundRobin = settings.routingStrategy == AiRoutingStrategy.SMART_HYBRID

        for (provider in providers) {
            if (!provider.requiresApiKey) {
                if (!firebaseConfigured) {
                    errors.add("${provider.displayName}: Firebase not configured in this APK")
                    continue
                }
                val model = settings.modelNameFor(provider)
                try {
                    val result = invokeForProvider(provider, "", model)
                    lastRouteSummary["route"] = "${provider.shortBadge} ($model)"
                    return KeyAttemptResult(
                        value = result,
                        providerUsed = provider,
                        modelUsed = model,
                        keyIndexOneBased = 1,
                        totalKeysInProvider = 1,
                        failoverCount = failovers
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    errors.add("${provider.displayName}: ${e.localizedMessage ?: "failed"}")
                    failovers++
                    continue
                }
            }

            val keys = settings.apiKeysFor(provider)
            if (keys.isEmpty()) {
                errors.add("${provider.displayName}: No API keys configured")
                continue
            }

            val primaryModel = settings.modelNameFor(provider)
            val fallbackModel = provider.defaultModel.takeIf { it != primaryModel }
            val keyIndices = orderedKeyIndices(provider, keys)

            for (idx in keyIndices) {
                val key = keys[idx]
                var usedModel = primaryModel
                try {
                    val result = try {
                        invokeForProvider(provider, key, primaryModel)
                    } catch (firstErr: Exception) {
                        if (firstErr is CancellationException) throw firstErr
                        val msg = firstErr.message.orEmpty()
                        val isModelNotFound = msg.contains("HTTP 404") ||
                            msg.contains("not found", ignoreCase = true) ||
                            msg.contains("is not supported", ignoreCase = true)
                        if (isModelNotFound && fallbackModel != null) {
                            usedModel = fallbackModel
                            invokeForProvider(provider, key, fallbackModel)
                        } else {
                            throw firstErr
                        }
                    }

                    permanentFailedKeysByProvider[provider]?.remove(key)
                    cooldownUntilByProviderKey.remove("${provider.name}:$key")
                    activeIndexByProvider[provider] = if (isRoundRobin) {
                        (idx + 1) % keys.size
                    } else {
                        idx
                    }
                    lastRouteSummary["route"] = "${provider.shortBadge} #${idx + 1}/${keys.size}"
                    return KeyAttemptResult(
                        value = result,
                        providerUsed = provider,
                        modelUsed = usedModel,
                        keyIndexOneBased = idx + 1,
                        totalKeysInProvider = keys.size,
                        failoverCount = failovers
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    recordKeyFailure(provider, key, idx, keys.size, e)
                    failovers++
                    errors.add("${provider.shortBadge} Key #${idx + 1}: ${e.localizedMessage ?: "error"}")
                }
            }
        }

        throw IllegalStateException(
            "All configured keys/providers failed (${errors.size} attempt(s)). Last: ${errors.lastOrNull() ?: "Unknown error"}"
        )
    }

    private fun recordKeyFailure(
        provider: AiProvider,
        key: String,
        idx: Int,
        totalKeys: Int,
        e: Exception
    ) {
        val msg = e.message.orEmpty()
        if (isPermanentAuthError(msg)) {
            permanentFailedKeysByProvider
                .getOrPut(provider) { ConcurrentHashMap.newKeySet() }
                .add(key)
        } else {
            cooldownUntilByProviderKey["${provider.name}:$key"] =
                nowMsProvider() + RATE_LIMIT_COOLDOWN_MS
        }
        activeIndexByProvider[provider] = (idx + 1) % totalKeys
    }

    private fun isPermanentAuthError(message: String): Boolean {
        return message.contains("HTTP 401") ||
            message.contains("HTTP 403") ||
            message.contains("API_KEY_INVALID", ignoreCase = true) ||
            message.contains("invalid api key", ignoreCase = true)
    }
}
