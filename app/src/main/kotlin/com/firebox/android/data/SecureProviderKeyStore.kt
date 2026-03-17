@file:Suppress("DEPRECATION")

package com.firebox.android.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SecureProviderKeyStore(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey =
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val prefs =
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val blockstoreClient = Blockstore.getClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apiKeys = MutableStateFlow(readLocalApiKeys())

    val apiKeys: StateFlow<Map<Int, String>> = _apiKeys.asStateFlow()

    init {
        scope.launch {
            val mergedKeys = restoreApiKeysFromBlockStore(localKeys = _apiKeys.value)
            backupApiKeysToBlockStore(mergedKeys)
        }
    }

    fun getApiKey(providerId: Int): String =
        _apiKeys.value[providerId].orEmpty()

    suspend fun setApiKey(providerId: Int, apiKey: String) {
        val normalizedApiKey = apiKey.trim()
        if (normalizedApiKey.isBlank()) {
            deleteApiKey(providerId)
            return
        }

        writeLocalApiKey(providerId = providerId, apiKey = normalizedApiKey)
        _apiKeys.update { current -> current + (providerId to normalizedApiKey) }

        runCatching {
            storeApiKeyInBlockStore(providerId = providerId, apiKey = normalizedApiKey)
        }.onFailure { error ->
            Log.w(TAG, "Failed to store provider API key in Block Store for providerId=$providerId", error)
        }
    }

    suspend fun deleteApiKey(providerId: Int) {
        deleteLocalApiKey(providerId)
        _apiKeys.update { current -> current - providerId }

        runCatching {
            deleteApiKeyFromBlockStore(providerId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete provider API key from Block Store for providerId=$providerId", error)
        }
    }

    private suspend fun restoreApiKeysFromBlockStore(localKeys: Map<Int, String>): Map<Int, String> {
        val restoredKeys =
            runCatching {
                retrieveApiKeysFromBlockStore()
            }.onFailure { error ->
                Log.w(TAG, "Failed to restore provider API keys from Block Store", error)
            }.getOrDefault(emptyMap())

        if (restoredKeys.isEmpty()) return localKeys

        val mergedKeys = localKeys + restoredKeys
        writeLocalApiKeys(restoredKeys)
        _apiKeys.value = mergedKeys
        return mergedKeys
    }

    private suspend fun backupApiKeysToBlockStore(apiKeys: Map<Int, String>) {
        apiKeys.forEach { (providerId, apiKey) ->
            runCatching {
                storeApiKeyInBlockStore(providerId = providerId, apiKey = apiKey)
            }.onFailure { error ->
                Log.w(TAG, "Failed to back up provider API key to Block Store for providerId=$providerId", error)
            }
        }
    }

    private fun readLocalApiKeys(): Map<Int, String> =
        prefs.all.entries
            .mapNotNull { (key, value) ->
                val providerId = parseProviderId(key) ?: return@mapNotNull null
                val apiKey = (value as? String)?.trim().orEmpty()
                if (apiKey.isBlank()) null else providerId to apiKey
            }
            .toMap()

    private fun writeLocalApiKeys(apiKeys: Map<Int, String>) {
        if (apiKeys.isEmpty()) return
        prefs.edit().apply {
            apiKeys.forEach { (providerId, apiKey) ->
                putString(apiKeyKey(providerId), apiKey)
            }
        }.apply()
    }

    private fun writeLocalApiKey(providerId: Int, apiKey: String) {
        prefs.edit().putString(apiKeyKey(providerId), apiKey).apply()
    }

    private fun deleteLocalApiKey(providerId: Int) {
        prefs.edit().remove(apiKeyKey(providerId)).apply()
    }

    private suspend fun retrieveApiKeysFromBlockStore(): Map<Int, String> {
        val request =
            RetrieveBytesRequest.Builder()
                .setRetrieveAll(true)
                .build()
        val response = blockstoreClient.retrieveBytes(request).awaitResult()
        return response.blockstoreDataMap.orEmpty()
            .mapNotNull { (key, data) ->
                val providerId = parseProviderId(key) ?: return@mapNotNull null
                val apiKey = data.bytes.toString(Charsets.UTF_8).trim()
                if (apiKey.isBlank()) null else providerId to apiKey
            }
            .toMap()
    }

    private suspend fun storeApiKeyInBlockStore(providerId: Int, apiKey: String) {
        val data =
            StoreBytesData.Builder()
                .setKey(apiKeyKey(providerId))
                .setBytes(apiKey.toByteArray(Charsets.UTF_8))
                .setShouldBackupToCloud(true)
                .build()
        blockstoreClient.storeBytes(data).awaitResult()
    }

    private suspend fun deleteApiKeyFromBlockStore(providerId: Int) {
        val request =
            DeleteBytesRequest.Builder()
                .setKeys(listOf(apiKeyKey(providerId)))
                .build()
        blockstoreClient.deleteBytes(request).awaitResult()
    }

    private fun parseProviderId(key: String): Int? {
        if (!key.startsWith(API_KEY_PREFIX)) return null
        return key.removePrefix(API_KEY_PREFIX).toIntOrNull()
    }

    private fun apiKeyKey(providerId: Int): String = "$API_KEY_PREFIX$providerId"

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                when {
                    task.isSuccessful -> continuation.resume(task.result)
                    task.isCanceled -> continuation.cancel()
                    else -> continuation.resumeWithException(
                        task.exception ?: IllegalStateException("Task failed without exception")
                    )
                }
            }
        }

    private companion object {
        const val TAG = "SecureProviderKeyStore"
        const val PREFS_NAME = "firebox_secure"
        const val API_KEY_PREFIX = "provider_api_key_"
    }
}
