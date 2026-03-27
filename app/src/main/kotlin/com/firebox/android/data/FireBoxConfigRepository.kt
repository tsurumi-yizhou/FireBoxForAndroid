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
                provider.copy(
                    enabled = true,
                    apiKey = apiKeys[provider.id].orEmpty(),
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
            val now = System.currentTimeMillis()
            val newProvider =
                ProviderConfig(
                    id = nextId,
                    type = ProviderType.OpenAI,
                    name = "Provider #$nextId",
                    baseUrl = "",
                    enabled = true,
                    createdAtMs = now,
                    updatedAtMs = now,
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
        if (normalizedProvider.apiKey.isBlank()) {
            secureKeyStore.deleteApiKey(normalizedProvider.id)
        } else {
            secureKeyStore.setApiKey(normalizedProvider.id, normalizedProvider.apiKey)
        }
        storage.updateProviders { current ->
            val now = System.currentTimeMillis()
            val updated =
                if (current.any { it.id == normalizedProvider.id }) {
                    current.map { existing ->
                        if (existing.id == normalizedProvider.id) {
                            normalizedProvider.copy(
                                createdAtMs = existing.createdAtMs,
                                updatedAtMs = now,
                            )
                        } else {
                            existing
                        }
                    }
                } else {
                    current + normalizedProvider.copy(createdAtMs = now, updatedAtMs = now)
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
            val now = System.currentTimeMillis()
            val newRule =
                RouteRule(
                    id = nextId,
                    virtualModelId = "",
                    strategy = RouteStrategy.Failover,
                    candidates = emptyList(),
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            val updated =
                listOf(newRule) + current
            updated
        }
    }

    suspend fun upsertRouteRule(rule: RouteRule) {
        storage.updateRoutes { current ->
            val now = System.currentTimeMillis()
            val updated =
                if (current.any { it.id == rule.id }) {
                    current.map { existing ->
                        if (existing.id == rule.id) {
                            rule.copy(
                                createdAtMs = existing.createdAtMs,
                                updatedAtMs = now,
                            )
                        } else {
                            existing
                        }
                    }
                } else {
                    current + rule.copy(createdAtMs = now, updatedAtMs = now)
                }
            updated
        }
    }

    suspend fun deleteRouteRule(ruleId: Int) {
        storage.updateRoutes { current ->
            current.filterNot { it.id == ruleId }
        }
    }

    suspend fun updateClientAccessAllowed(
        accessId: Int,
        isAllowed: Boolean,
        deniedUntilUtc: String? = null,
    ) {
        storage.updateClientAccessRecords { current ->
            current.map { record ->
                if (record.id == accessId) {
                    record.copy(
                        isAllowed = isAllowed,
                        deniedUntilUtc = if (isAllowed) null else deniedUntilUtc,
                    )
                } else {
                    record
                }
            }
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
        val packageRecords = current.filter { it.packageName == packageName }
        val existingRecord = packageRecords.maxByOrNull { it.lastSeenAtMs }
        val updated =
            if (existingRecord != null) {
                current.map { existing ->
                    if (existing.id != existingRecord.id) {
                        existing
                    } else {
                        existing.copy(
                            processName = existing.processName.ifBlank { packageName },
                            executablePath = existing.executablePath.ifBlank { packageName },
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
                val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
                current +
                        ClientAccessRecord(
                            id = nextId,
                            packageName = packageName,
                            processName = packageName,
                            executablePath = packageName,
                            lastCallingUid = callingUid,
                            firstSeenAtMs = seenAtMs,
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


