package com.firebox.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.firebox.android.model.ConfigDefaults
import com.firebox.android.model.ClientAccessRecord
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.QuickToolModelConfig
import com.firebox.android.model.RouteRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal interface FireBoxConfigStorage {
    val clientAccessRecords: Flow<List<ClientAccessRecord>>
    val providers: Flow<List<ProviderConfig>>
    val routes: Flow<List<RouteRule>>
    val quickToolModel: Flow<QuickToolModelConfig>

    suspend fun updateClientAccessRecords(transform: (List<ClientAccessRecord>) -> List<ClientAccessRecord>)
    suspend fun updateProviders(transform: (List<ProviderConfig>) -> List<ProviderConfig>)
    suspend fun updateRoutes(transform: (List<RouteRule>) -> List<RouteRule>)
    suspend fun updateQuickToolModel(transform: (QuickToolModelConfig) -> QuickToolModelConfig)
}

internal class DataStoreFireBoxConfigStorage(
    context: Context,
) : FireBoxConfigStorage {
    private val appContext = context.applicationContext

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override val providers: Flow<List<ProviderConfig>> =
        appContext.fireBoxPreferencesDataStore.data
            .map { prefs -> readProviders(prefs) }

    override val clientAccessRecords: Flow<List<ClientAccessRecord>> =
        appContext.fireBoxPreferencesDataStore.data
            .map { prefs -> readClientAccessRecords(prefs) }
            .distinctUntilChanged()

    override val routes: Flow<List<RouteRule>> =
        appContext.fireBoxPreferencesDataStore.data
            .map { prefs -> readRoutes(prefs) }
            .distinctUntilChanged()

    override val quickToolModel: Flow<QuickToolModelConfig> =
        appContext.fireBoxPreferencesDataStore.data
            .map { prefs -> readQuickToolModel(prefs) }
            .distinctUntilChanged()

    override suspend fun updateClientAccessRecords(transform: (List<ClientAccessRecord>) -> List<ClientAccessRecord>) {
        appContext.fireBoxPreferencesDataStore.edit { prefs ->
            val updated = transform(readClientAccessRecords(prefs))
            prefs[clientAccessRecordsKey] = encodeClientAccessRecords(updated)
        }
    }

    override suspend fun updateProviders(transform: (List<ProviderConfig>) -> List<ProviderConfig>) {
        appContext.fireBoxPreferencesDataStore.edit { prefs ->
            val updated = transform(readProviders(prefs))
            prefs[providersKey] = encodeProviders(updated)
        }
    }

    override suspend fun updateRoutes(transform: (List<RouteRule>) -> List<RouteRule>) {
        appContext.fireBoxPreferencesDataStore.edit { prefs ->
            val updated = transform(readRoutes(prefs))
            prefs[routesKey] = encodeRoutes(updated)
        }
    }

    override suspend fun updateQuickToolModel(transform: (QuickToolModelConfig) -> QuickToolModelConfig) {
        appContext.fireBoxPreferencesDataStore.edit { prefs ->
            val updated = transform(readQuickToolModel(prefs))
            prefs[quickToolModelKey] = encodeQuickToolModel(updated)
        }
    }

    private fun readProviders(prefs: Preferences): List<ProviderConfig> {
        val encoded = prefs[providersKey]
        if (encoded.isNullOrBlank()) return ConfigDefaults.providers()
        return decodeProviders(encoded) ?: ConfigDefaults.providers()
    }

    private fun readClientAccessRecords(prefs: Preferences): List<ClientAccessRecord> {
        val encoded = prefs[clientAccessRecordsKey]
        if (encoded.isNullOrBlank()) return ConfigDefaults.clientAccessRecords()
        return decodeClientAccessRecords(encoded) ?: ConfigDefaults.clientAccessRecords()
    }

    private fun readRoutes(prefs: Preferences): List<RouteRule> {
        val encoded = prefs[routesKey]
        if (encoded.isNullOrBlank()) return ConfigDefaults.routes()
        return decodeRoutes(encoded) ?: ConfigDefaults.routes()
    }

    private fun readQuickToolModel(prefs: Preferences): QuickToolModelConfig {
        val encoded = prefs[quickToolModelKey]
        if (encoded.isNullOrBlank()) return ConfigDefaults.quickToolModel()
        return decodeQuickToolModel(encoded) ?: ConfigDefaults.quickToolModel()
    }

    private fun decodeProviders(encoded: String): List<ProviderConfig>? =
        runCatching {
            json.decodeFromString(ListSerializer(ProviderConfig.serializer()), encoded)
        }.getOrNull()

    private fun encodeProviders(providers: List<ProviderConfig>): String =
        json.encodeToString(ListSerializer(ProviderConfig.serializer()), providers)

    private fun decodeClientAccessRecords(encoded: String): List<ClientAccessRecord>? =
        runCatching {
            json.decodeFromString(ListSerializer(ClientAccessRecord.serializer()), encoded)
        }.getOrNull()

    private fun encodeClientAccessRecords(records: List<ClientAccessRecord>): String =
        json.encodeToString(ListSerializer(ClientAccessRecord.serializer()), records)

    private fun decodeRoutes(encoded: String): List<RouteRule>? =
        runCatching {
            val decoded = json.decodeFromString(ListSerializer(RouteRule.serializer()), encoded)
            // Migrate legacy rules without IDs (id=0). Keep migration consistent across read/write paths.
            if (decoded.any { it.id == 0 }) {
                var nextId = decoded.maxOfOrNull { it.id }?.coerceAtLeast(0) ?: 0
                decoded.map { rule -> if (rule.id == 0) rule.copy(id = ++nextId) else rule }
            } else {
                decoded
            }
        }.getOrNull()

    private fun encodeRoutes(routes: List<RouteRule>): String =
        json.encodeToString(ListSerializer(RouteRule.serializer()), routes)

    private fun decodeQuickToolModel(encoded: String): QuickToolModelConfig? =
        runCatching {
            json.decodeFromString(QuickToolModelConfig.serializer(), encoded)
        }.getOrNull()

    private fun encodeQuickToolModel(config: QuickToolModelConfig): String =
        json.encodeToString(QuickToolModelConfig.serializer(), config)

    private companion object {
        val clientAccessRecordsKey = stringPreferencesKey("client_access_records_json")
        val providersKey = stringPreferencesKey("providers_json")
        val routesKey = stringPreferencesKey("routes_json")
        val quickToolModelKey = stringPreferencesKey("quick_tool_model_json")
    }
}
