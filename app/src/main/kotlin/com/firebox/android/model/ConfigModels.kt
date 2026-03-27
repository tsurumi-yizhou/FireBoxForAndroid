package com.firebox.android.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class ProviderType(val displayName: String) {
    OpenAI("OpenAI"),
    Anthropic("Anthropic"),
    Gemini("Gemini"),
}

data class ClientConnectionInfo(
    val callingUid: Int,
    val packageName: String,
    val connectedAtMs: Long,
    val requestCount: Long,
    val hasActiveStream: Boolean,
    val callbackCount: Int,
) {
    val isActive: Boolean
        get() = callbackCount > 0 || hasActiveStream
}

@Serializable
data class ClientAccessRecord(
    val id: Int = 0,
    val packageName: String,
    val processName: String = "",
    val executablePath: String = "",
    val lastCallingUid: Int = -1,
    val firstSeenAtMs: Long = 0L,
    val lastSeenAtMs: Long = 0L,
    val lastConnectedAtMs: Long = 0L,
    val lastRequestAtMs: Long = 0L,
    val totalConnections: Long = 0L,
    val totalRequests: Long = 0L,
    val isAllowed: Boolean = true,
    val deniedUntilUtc: String? = null,
)

@Serializable
data class ProviderModelConfig(
    val modelId: String,
    val enabled: Boolean = true,
)

@Serializable
data class ProviderConfig(
    val id: Int,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean,
    val enabledModels: List<String> = emptyList(),
    val models: List<ProviderModelConfig> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    @Transient val apiKey: String = "",
)

@Serializable
enum class RouteStrategy(val displayName: String) {
    Failover("Failover"),
    Random("Random"),
}

@Serializable
enum class RouteMediaFormat(val displayName: String) {
    Image("Image"),
    Video("Video"),
    Audio("Audio"),
}

@Serializable
data class RouteModelCapabilities(
    val reasoning: Boolean = false,
    val toolCalling: Boolean = false,
    val inputFormats: List<RouteMediaFormat> = emptyList(),
    val outputFormats: List<RouteMediaFormat> = emptyList(),
)

@Serializable
data class ModelTarget(
    val providerId: Int = 0,
    val modelId: String = "",
    val provider: ProviderType? = null,
    val model: String? = null,
)

@Serializable
data class RouteRule(
    val id: Int = 0,
    val virtualModelId: String,
    val strategy: RouteStrategy,
    val candidates: List<ModelTarget>,
    val capabilities: RouteModelCapabilities = RouteModelCapabilities(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

object ConfigDefaults {
    fun providers(): List<ProviderConfig> = emptyList()

    fun routes(): List<RouteRule> = emptyList()

    fun clientAccessRecords(): List<ClientAccessRecord> = emptyList()
}

object ModelPricing {
    fun lookupMicrosPerToken(providerType: ProviderType, modelId: String): Pair<Long, Long> {
        return 0L to 0L
    }
}
