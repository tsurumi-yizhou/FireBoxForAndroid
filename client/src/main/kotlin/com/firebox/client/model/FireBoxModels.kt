package com.firebox.client.model

import kotlinx.serialization.KSerializer

data class FireBoxMessage(
    val role: String,
    val content: String,
)

data class FireBoxChatRequest(
    val virtualModelId: String,
    val messages: List<FireBoxMessage>,
    val temperature: Float = -1f,
    val maxOutputTokens: Int = -1,
)

data class FireBoxProviderSelection(
    val providerId: Int,
    val providerType: String,
    val providerName: String,
    val modelId: String,
)

data class FireBoxUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
)

data class FireBoxChatResponse(
    val virtualModelId: String,
    val message: FireBoxMessage,
    val selection: FireBoxProviderSelection,
    val usage: FireBoxUsage,
    val finishReason: String,
)

data class FireBoxChatResult(
    val response: FireBoxChatResponse?,
    val error: FireBoxSdkError?,
) {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}

data class FireBoxSdkError(
    val code: Int,
    val message: String,
    val providerType: String?,
    val providerModelId: String?,
) {
    companion object {
        const val SECURITY = 1
        const val INVALID_ARGUMENT = 2
        const val NO_ROUTE = 3
        const val NO_CANDIDATE = 4
        const val PROVIDER_ERROR = 5
        const val TIMEOUT = 6
        const val INTERNAL = 7
        const val CANCELLED = 8
    }
}

data class FireBoxModelCandidateInfo(
    val providerId: Int,
    val providerType: String,
    val providerName: String,
    val baseUrl: String,
    val modelId: String,
    val enabledInConfig: Boolean,
    val capabilitySupported: Boolean,
)

data class FireBoxModelInfo(
    val virtualModelId: String,
    val strategy: String,
    val candidates: List<FireBoxModelCandidateInfo>,
    val available: Boolean,
)

data class FireBoxEmbedding(
    val index: Int,
    val vector: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FireBoxEmbedding) return false
        return index == other.index && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * index + vector.contentHashCode()
}

data class FireBoxEmbeddingRequest(
    val virtualModelId: String,
    val input: List<String>,
)

data class FireBoxEmbeddingResponse(
    val virtualModelId: String,
    val embeddings: List<FireBoxEmbedding>,
    val selection: FireBoxProviderSelection,
    val usage: FireBoxUsage,
)

data class FireBoxEmbeddingResult(
    val response: FireBoxEmbeddingResponse?,
    val error: FireBoxSdkError?,
) {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}

data class FireBoxStreamEvent(
    val requestId: Long,
    val type: Type,
    val deltaText: String?,
    val selection: FireBoxProviderSelection?,
    val usage: FireBoxUsage?,
    val response: FireBoxChatResponse?,
    val error: FireBoxSdkError?,
) {
    enum class Type {
        STARTED,
        DELTA,
        USAGE,
        COMPLETED,
        ERROR,
        CANCELLED,
    }
}

data class FireBoxFunctionSpec<I, O>(
    val virtualModelId: String,
    val name: String,
    val description: String,
    val inputSerializer: KSerializer<I>,
    val outputSerializer: KSerializer<O>,
    val temperature: Float = 0f,
    val maxOutputTokens: Int = -1,
)

data class FireBoxFunctionResponse<O>(
    val virtualModelId: String,
    val output: O,
    val rawJson: String,
    val selection: FireBoxProviderSelection,
    val usage: FireBoxUsage,
    val finishReason: String,
)

data class FireBoxFunctionResult<O>(
    val response: FireBoxFunctionResponse<O>?,
    val error: FireBoxSdkError?,
) {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}
