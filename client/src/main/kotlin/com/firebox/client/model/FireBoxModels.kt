package com.firebox.client.model

import java.io.File
import kotlinx.serialization.KSerializer

data class FireBoxMessageAttachment(
    val mediaFormat: FireBoxMediaFormat,
    val mimeType: String,
    val filePath: String,
    val fileName: String? = File(filePath).name,
    val sizeBytes: Long = File(filePath).length(),
)

data class FireBoxMessage(
    val role: String,
    val content: String,
    val attachments: List<FireBoxMessageAttachment> = emptyList(),
)

data class FireBoxChatRequest(
    val modelId: String,
    val messages: List<FireBoxMessage>,
    val temperature: Float?,
    val maxOutputTokens: Int?,
    val reasoningEffort: FireBoxReasoningEffort? = null,
)

data class FireBoxUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
)

data class FireBoxChatResponse(
    val modelId: String,
    val message: FireBoxMessage,
    val reasoningText: String?,
    val usage: FireBoxUsage,
    val finishReason: String,
)

data class FireBoxChatResult(
    val response: FireBoxChatResponse?,
    val error: String?,
) {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}

enum class FireBoxMediaFormat {
    Image,
    Video,
    Audio,
}

data class FireBoxModelCapabilities(
    val reasoning: Boolean,
    val toolCalling: Boolean,
    val inputFormats: List<FireBoxMediaFormat>,
    val outputFormats: List<FireBoxMediaFormat>,
)

data class FireBoxModelInfo(
    val modelId: String,
    val capabilities: FireBoxModelCapabilities,
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
    val modelId: String,
    val input: List<String>,
)

data class FireBoxEmbeddingResponse(
    val modelId: String,
    val embeddings: List<FireBoxEmbedding>,
    val usage: FireBoxUsage,
)

data class FireBoxEmbeddingResult(
    val response: FireBoxEmbeddingResponse?,
    val error: String?,
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
    val reasoningText: String?,
    val usage: FireBoxUsage?,
    val modelId: String?,
    val message: FireBoxMessage?,
    val finishReason: String?,
    val error: String?,
) {
    enum class Type {
        STARTED,
        DELTA,
        USAGE,
        COMPLETED,
        ERROR,
        CANCELLED,
        REASONING_DELTA,
    }
}

data class FireBoxFunctionSpec<I, O>(
    val name: String,
    val description: String,
    val inputSerializer: KSerializer<I>,
    val outputSerializer: KSerializer<O>,
    val temperature: Float?,
    val maxOutputTokens: Int?,
)

data class FireBoxFunctionResponse<O>(
    val modelId: String,
    val output: O,
    val rawJson: String,
    val usage: FireBoxUsage,
    val finishReason: String,
)

data class FireBoxFunctionResult<O>(
    val response: FireBoxFunctionResponse<O>?,
    val error: String?,
) {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}
