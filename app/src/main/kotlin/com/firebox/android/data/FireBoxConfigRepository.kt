package com.firebox.android.data

import android.content.Context
import com.firebox.android.ai.ProviderBaseUrlNormalizer
import com.firebox.android.model.ClientAccessRecord
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.android.model.RouteRule
import com.firebox.android.model.RouteStrategy
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

class FireBoxConfigRepository internal constructor(
    private val storage: FireBoxConfigStorage,
    private val secureKeyStore: SecureProviderKeyStore,
) {
    constructor(context: Context) : this(
        storage = DataStoreFireBoxConfigStorage(context.applicationContext),
        secureKeyStore = SecureProviderKeyStore(context.applicationContext),
    )

    val clientAccessRecords: Flow<List<ClientAccessRecord>> =
        storage.clientAccessRecords.distinctUntilChanged()

    val providers: Flow<List<ProviderConfig>> =
        combine(storage.providers, secureKeyStore.apiKeys) { providers, apiKeys ->
            providers.map { provider ->
                val migratedEnabledModels =
                    provider.enabledModels.ifEmpty {
                        provider.models.filter { it.enabled }.map { it.modelId }
                    }
                provider.copy(
                    enabled = true,
                    apiKey = apiKeys[provider.id].orEmpty(),
                    enabledModels = migratedEnabledModels,
                    models = emptyList(),
                )
            }
        }
            .distinctUntilChanged()

    val routes: Flow<List<RouteRule>> =
        storage.routes.distinctUntilChanged()

    suspend fun recordClientConnected(
        packageName: String,
        callingUid: Int,
        connectedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (packageName.isBlank()) return
        storage.updateClientAccessRecords { current ->
            upsertClientAccessRecord(
                current = current,
                packageName = packageName,
                callingUid = callingUid,
                seenAtMs = connectedAtMs,
                connectedAtMs = connectedAtMs,
            )
        }
    }

    suspend fun recordClientRequest(
        packageName: String,
        callingUid: Int,
        requestedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (packageName.isBlank()) return
        storage.updateClientAccessRecords { current ->
            upsertClientAccessRecord(
                current = current,
                packageName = packageName,
                callingUid = callingUid,
                seenAtMs = requestedAtMs,
                requestedAtMs = requestedAtMs,
            )
        }
    }

    suspend fun addProvider() {
        storage.updateProviders { current ->
            val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
            val newProvider =
                ProviderConfig(
                    id = nextId,
                    type = ProviderType.OpenAI,
                    name = "Provider #$nextId",
                    baseUrl = "",
                    enabled = true,
                )
            val updated =
                listOf(newProvider) + current
            updated
        }
    }

    suspend fun nextProviderId(): Int =
        (storage.providers.first().maxOfOrNull { it.id } ?: 0) + 1

    suspend fun upsertProvider(provider: ProviderConfig) {
        val normalizedBaseUrlInput = provider.baseUrl.trim()
        val normalizedBaseUrl =
            when {
                normalizedBaseUrlInput.isBlank() -> ""
                normalizedBaseUrlInput.equals("https://", ignoreCase = true) -> ""
                normalizedBaseUrlInput.equals("http://", ignoreCase = true) -> ""
                else -> {
                    runCatching {
                        ProviderBaseUrlNormalizer.normalizeProviderBaseUrl(provider.type, normalizedBaseUrlInput)
                    }.getOrElse { normalizedBaseUrlInput }
                }
            }
        val normalizedProvider =
            provider.copy(
                enabled = true,
                baseUrl = normalizedBaseUrl,
            )
        if (normalizedProvider.apiKey.isNotBlank()) {
            secureKeyStore.setApiKey(normalizedProvider.id, normalizedProvider.apiKey)
        }
        storage.updateProviders { current ->
            val updated =
                if (current.any { it.id == normalizedProvider.id }) {
                    current.map { if (it.id == normalizedProvider.id) normalizedProvider else it }
                } else {
                    current + normalizedProvider
                }
            updated
        }
    }

    suspend fun deleteProvider(providerId: Int) {
        secureKeyStore.deleteApiKey(providerId)
        storage.updateProviders { current ->
            current.filterNot { it.id == providerId }
        }
    }

    suspend fun addRouteRule() {
        storage.updateRoutes { current ->
            val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
            val newRule =
                RouteRule(
                    id = nextId,
                    virtualModelId = "",
                    strategy = RouteStrategy.Failover,
                    candidates = emptyList(),
                )
            val updated =
                listOf(newRule) + current
            updated
        }
    }

    suspend fun upsertRouteRule(rule: RouteRule) {
        storage.updateRoutes { current ->
            val updated =
                if (current.any { it.id == rule.id }) {
                    current.map { if (it.id == rule.id) rule else it }
                } else {
                    current + rule
                }
            updated
        }
    }

    suspend fun deleteRouteRule(ruleId: Int) {
        storage.updateRoutes { current ->
            current.filterNot { it.id == ruleId }
        }
    }

    private fun upsertClientAccessRecord(
        current: List<ClientAccessRecord>,
        packageName: String,
        callingUid: Int,
        seenAtMs: Long,
        connectedAtMs: Long = 0L,
        requestedAtMs: Long = 0L,
    ): List<ClientAccessRecord> {
        val updated =
            if (current.any { it.packageName == packageName }) {
                current.map { existing ->
                    if (existing.packageName != packageName) {
                        existing
                    } else {
                        existing.copy(
                            lastCallingUid = callingUid,
                            lastSeenAtMs = maxOf(existing.lastSeenAtMs, seenAtMs),
                            lastConnectedAtMs =
                                if (connectedAtMs > 0L) maxOf(
                                    existing.lastConnectedAtMs,
                                    connectedAtMs
                                ) else existing.lastConnectedAtMs,
                            lastRequestAtMs =
                                if (requestedAtMs > 0L) maxOf(
                                    existing.lastRequestAtMs,
                                    requestedAtMs
                                ) else existing.lastRequestAtMs,
                            totalConnections = existing.totalConnections + if (connectedAtMs > 0L) 1 else 0,
                            totalRequests = existing.totalRequests + if (requestedAtMs > 0L) 1 else 0,
                        )
                    }
                }
            } else {
                current +
                        ClientAccessRecord(
                            packageName = packageName,
                            lastCallingUid = callingUid,
                            lastSeenAtMs = seenAtMs,
                            lastConnectedAtMs = connectedAtMs,
                            lastRequestAtMs = requestedAtMs,
                            totalConnections = if (connectedAtMs > 0L) 1 else 0,
                            totalRequests = if (requestedAtMs > 0L) 1 else 0,
                        )
            }
        return updated.sortedByDescending { it.lastSeenAtMs }
    }
}


