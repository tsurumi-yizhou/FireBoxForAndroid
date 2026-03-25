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
import com.firebox.client.model.FireBoxModelCandidateInfo
import com.firebox.client.model.FireBoxModelCapabilities
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxProviderSelection
import com.firebox.client.model.FireBoxSdkError
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
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallRequest
import com.firebox.core.FunctionCallResponse
import com.firebox.core.FunctionCallResult
import com.firebox.core.ModelCandidateInfo
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelMediaFormat
import com.firebox.core.ProviderSelection
import com.firebox.core.ReasoningEffort
import com.firebox.core.Usage
import com.firebox.core.VirtualModelInfo
import java.io.File
import kotlinx.serialization.KSerializer

internal fun FireBoxChatRequest.toCore(): ChatCompletionRequest =
    ChatCompletionRequest(
        virtualModelId = virtualModelId,
        messages = messages.map(FireBoxMessage::toCore),
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
        reasoningEffort = reasoningEffort?.toCore(),
    )

internal fun FireBoxEmbeddingRequest.toCore(): EmbeddingRequest =
    EmbeddingRequest(
        virtualModelId = virtualModelId,
        input = input,
    )

internal fun <I, O> FireBoxFunctionSpec<I, O>.toCore(input: I): FunctionCallRequest =
    FunctionCallRequest(
        virtualModelId = virtualModelId,
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
        fileDescriptor = descriptor,
        sizeBytes = sizeBytes.takeIf { it >= 0L } ?: file.length(),
    )
}

internal fun VirtualModelInfo.toClient(): FireBoxModelInfo =
    FireBoxModelInfo(
        virtualModelId = virtualModelId,
        strategy = strategy,
        capabilities = capabilities.toClient(),
        candidates = candidates.map(ModelCandidateInfo::toClient),
        available = available,
    )

private fun ModelCapabilities.toClient(): FireBoxModelCapabilities =
    FireBoxModelCapabilities(
        reasoning = reasoning,
        toolCalling = toolCalling,
        inputFormats = inputFormats.map(ModelMediaFormat::toClient),
        outputFormats = outputFormats.map(ModelMediaFormat::toClient),
    )

private fun ModelMediaFormat.toClient(): FireBoxMediaFormat =
    when (this) {
        ModelMediaFormat.Image -> FireBoxMediaFormat.Image
        ModelMediaFormat.Video -> FireBoxMediaFormat.Video
        ModelMediaFormat.Audio -> FireBoxMediaFormat.Audio
    }

private fun FireBoxMediaFormat.toCore(): ModelMediaFormat =
    when (this) {
        FireBoxMediaFormat.Image -> ModelMediaFormat.Image
        FireBoxMediaFormat.Video -> ModelMediaFormat.Video
        FireBoxMediaFormat.Audio -> ModelMediaFormat.Audio
    }

private fun FireBoxReasoningEffort.toCore(): ReasoningEffort =
    when (this) {
        FireBoxReasoningEffort.Low -> ReasoningEffort.Low
        FireBoxReasoningEffort.Medium -> ReasoningEffort.Medium
        FireBoxReasoningEffort.High -> ReasoningEffort.High
    }

private fun ModelCandidateInfo.toClient(): FireBoxModelCandidateInfo =
    FireBoxModelCandidateInfo(
        providerId = providerId,
        providerType = providerType,
        providerName = providerName,
        baseUrl = baseUrl,
        modelId = modelId,
        enabledInConfig = enabledInConfig,
        capabilitySupported = capabilitySupported,
    )

internal fun ChatCompletionResponse.toClient(): FireBoxChatResponse =
    FireBoxChatResponse(
        virtualModelId = virtualModelId,
        message = message.toClient(),
        reasoningText = reasoningText,
        selection = selection.toClient(),
        usage = usage.toClient(),
        finishReason = finishReason,
    )

internal fun ChatCompletionResult.toClient(): FireBoxChatResult =
    FireBoxChatResult(
        response = response?.toClient(),
        error = error?.toClient(),
    )

internal fun EmbeddingResponse.toClient(): FireBoxEmbeddingResponse =
    FireBoxEmbeddingResponse(
        virtualModelId = virtualModelId,
        embeddings = embeddings.map(Embedding::toClient),
        selection = selection.toClient(),
        usage = usage.toClient(),
    )

internal fun EmbeddingResult.toClient(): FireBoxEmbeddingResult =
    FireBoxEmbeddingResult(
        response = response?.toClient(),
        error = error?.toClient(),
    )

internal fun <O> FunctionCallResult.toClient(outputSerializer: KSerializer<O>): FireBoxFunctionResult<O> =
    FireBoxFunctionResult(
        response = response?.toClient(outputSerializer),
        error = error?.toClient(),
    )

internal fun CoreChatStreamEvent.toClient(): FireBoxStreamEvent =
    FireBoxStreamEvent(
        requestId = requestId,
        type = type.toClientStreamType(),
        deltaText = deltaText,
        reasoningText = reasoningText,
        selection = selection?.toClient(),
        usage = usage?.toClient(),
        response = response?.toClient(),
        error = error?.toClient(),
    )

private fun ChatMessage.toClient(): FireBoxMessage =
    FireBoxMessage(
        role = role,
        content = content,
        attachments = emptyList(),
    )

private fun ProviderSelection.toClient(): FireBoxProviderSelection =
    FireBoxProviderSelection(
        providerId = providerId,
        providerType = providerType,
        providerName = providerName,
        modelId = modelId,
    )

private fun <O> FunctionCallResponse.toClient(outputSerializer: KSerializer<O>): FireBoxFunctionResponse<O> =
    FireBoxFunctionResponse(
        virtualModelId = virtualModelId,
        output = FunctionSchemaSupport.decode(outputJson, outputSerializer),
        rawJson = outputJson,
        selection = selection.toClient(),
        usage = usage.toClient(),
        finishReason = finishReason,
    )

private fun Usage.toClient(): FireBoxUsage =
    FireBoxUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )

private fun FireBoxError.toClient(): FireBoxSdkError =
    FireBoxSdkError(
        code = code,
        message = message,
        providerType = providerType,
        providerModelId = providerModelId,
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
