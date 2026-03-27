package com.firebox.client.internal

import android.os.ParcelFileDescriptor
import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxChatResponse
import com.firebox.client.model.FireBoxChatResult
import com.firebox.client.model.FireBoxReasoningEffort
import com.firebox.client.model.FireBoxEmbedding
import com.firebox.client.model.FireBoxEmbeddingRequest
import com.firebox.client.model.FireBoxEmbeddingResponse
import com.firebox.client.model.FireBoxEmbeddingResult
import com.firebox.client.model.FireBoxFunctionResponse
import com.firebox.client.model.FireBoxFunctionResult
import com.firebox.client.model.FireBoxFunctionSpec
import com.firebox.client.model.FireBoxMediaFormat
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxMessageAttachment
import com.firebox.client.model.FireBoxModelCapabilities
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxStreamEvent
import com.firebox.client.model.FireBoxUsage
import com.firebox.core.ChatAttachment
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.ChatStreamEvent as CoreChatStreamEvent
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingRequest
import com.firebox.core.EmbeddingResponse
import com.firebox.core.EmbeddingResult
import com.firebox.core.FunctionCallRequest
import com.firebox.core.FunctionCallResponse
import com.firebox.core.FunctionCallResult
import com.firebox.core.MediaFormat
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelInfo
import com.firebox.core.ReasoningEffort
import com.firebox.core.Usage
import java.io.File
import kotlinx.serialization.KSerializer

internal fun FireBoxChatRequest.toCore(): ChatCompletionRequest =
    ChatCompletionRequest(
        modelId = modelId,
        messages = messages.map(FireBoxMessage::toCore),
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
        reasoningEffort = reasoningEffort?.toCore(),
    )

internal fun FireBoxEmbeddingRequest.toCore(): EmbeddingRequest =
    EmbeddingRequest(
        modelId = modelId,
        input = input,
    )

internal fun <I, O> FireBoxFunctionSpec<I, O>.toCore(input: I): FunctionCallRequest =
    FunctionCallRequest(
        functionName = name,
        functionDescription = description,
        inputJson = FunctionSchemaSupport.encode(input, inputSerializer),
        inputSchemaJson = FunctionSchemaSupport.schema(inputSerializer),
        outputSchemaJson = FunctionSchemaSupport.schema(outputSerializer),
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
    )

private fun FireBoxMessage.toCore(): ChatMessage =
    ChatMessage(
        role = role,
        content = content,
        attachments = attachments.map(FireBoxMessageAttachment::toCore),
    )

private fun FireBoxMessageAttachment.toCore(): ChatAttachment {
    val file = File(filePath)
    val descriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("Unable to open attachment: $filePath")
    return ChatAttachment(
        mediaFormat = mediaFormat.toCore(),
        mimeType = mimeType,
        fileName = fileName,
        data = descriptor,
        sizeBytes = sizeBytes.takeIf { it >= 0L } ?: file.length(),
    )
}

internal fun ModelInfo.toClient(): FireBoxModelInfo =
    FireBoxModelInfo(
        modelId = modelId,
        capabilities = capabilities.toClient(),
        available = available,
    )

private fun ModelCapabilities.toClient(): FireBoxModelCapabilities =
    FireBoxModelCapabilities(
        reasoning = reasoning,
        toolCalling = toolCalling,
        inputFormats = inputFormats.map(MediaFormat::toClient),
        outputFormats = outputFormats.map(MediaFormat::toClient),
    )

private fun MediaFormat.toClient(): FireBoxMediaFormat =
    when (this) {
        MediaFormat.Image -> FireBoxMediaFormat.Image
        MediaFormat.Video -> FireBoxMediaFormat.Video
        MediaFormat.Audio -> FireBoxMediaFormat.Audio
    }

private fun FireBoxMediaFormat.toCore(): MediaFormat =
    when (this) {
        FireBoxMediaFormat.Image -> MediaFormat.Image
        FireBoxMediaFormat.Video -> MediaFormat.Video
        FireBoxMediaFormat.Audio -> MediaFormat.Audio
    }

private fun FireBoxReasoningEffort.toCore(): ReasoningEffort =
    when (this) {
        FireBoxReasoningEffort.Low -> ReasoningEffort.Low
        FireBoxReasoningEffort.Medium -> ReasoningEffort.Medium
        FireBoxReasoningEffort.High -> ReasoningEffort.High
    }

internal fun ChatCompletionResponse.toClient(): FireBoxChatResponse =
    FireBoxChatResponse(
        modelId = modelId,
        message = message.toClient(),
        reasoningText = reasoningText,
        usage = usage.toClient(),
        finishReason = finishReason,
    )

internal fun ChatCompletionResult.toClient(): FireBoxChatResult =
    FireBoxChatResult(
        response = response?.toClient(),
        error = error,
    )

internal fun EmbeddingResponse.toClient(): FireBoxEmbeddingResponse =
    FireBoxEmbeddingResponse(
        modelId = modelId,
        embeddings = embeddings.map(Embedding::toClient),
        usage = usage.toClient(),
    )

internal fun EmbeddingResult.toClient(): FireBoxEmbeddingResult =
    FireBoxEmbeddingResult(
        response = response?.toClient(),
        error = error,
    )

internal fun <O> FunctionCallResult.toClient(outputSerializer: KSerializer<O>): FireBoxFunctionResult<O> =
    FireBoxFunctionResult(
        response = response?.toClient(outputSerializer),
        error = error,
    )

internal fun CoreChatStreamEvent.toClient(): FireBoxStreamEvent =
    FireBoxStreamEvent(
        requestId = requestId,
        type = type.toClientStreamType(),
        deltaText = deltaText,
        reasoningText = reasoningText,
        usage = usage?.toClient(),
        modelId = modelId,
        message = message?.toClient(),
        finishReason = finishReason,
        error = error,
    )

private fun ChatMessage.toClient(): FireBoxMessage =
    FireBoxMessage(
        role = role,
        content = content,
        attachments = emptyList(),
    )

private fun <O> FunctionCallResponse.toClient(outputSerializer: KSerializer<O>): FireBoxFunctionResponse<O> =
    FireBoxFunctionResponse(
        modelId = modelId,
        output = FunctionSchemaSupport.decode(outputJson, outputSerializer),
        rawJson = outputJson,
        usage = usage.toClient(),
        finishReason = finishReason,
    )

private fun Usage.toClient(): FireBoxUsage =
    FireBoxUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )

private fun Embedding.toClient(): FireBoxEmbedding =
    FireBoxEmbedding(
        index = index,
        vector = vector.copyOf(),
    )

private fun Int.toClientStreamType(): FireBoxStreamEvent.Type =
    when (this) {
        CoreChatStreamEvent.STARTED -> FireBoxStreamEvent.Type.STARTED
        CoreChatStreamEvent.DELTA -> FireBoxStreamEvent.Type.DELTA
        CoreChatStreamEvent.USAGE -> FireBoxStreamEvent.Type.USAGE
        CoreChatStreamEvent.COMPLETED -> FireBoxStreamEvent.Type.COMPLETED
        CoreChatStreamEvent.ERROR -> FireBoxStreamEvent.Type.ERROR
        CoreChatStreamEvent.CANCELLED -> FireBoxStreamEvent.Type.CANCELLED
        CoreChatStreamEvent.REASONING_DELTA -> FireBoxStreamEvent.Type.REASONING_DELTA
        else -> FireBoxStreamEvent.Type.ERROR
    }
