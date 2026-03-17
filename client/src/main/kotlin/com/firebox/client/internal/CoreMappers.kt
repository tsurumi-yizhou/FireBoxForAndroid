package com.firebox.client.internal

import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxChatResult
import com.firebox.client.model.FireBoxChatResponse
import com.firebox.client.model.FireBoxEmbedding
import com.firebox.client.model.FireBoxEmbeddingRequest
import com.firebox.client.model.FireBoxEmbeddingResult
import com.firebox.client.model.FireBoxEmbeddingResponse
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxModelCandidateInfo
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxProviderSelection
import com.firebox.client.model.FireBoxSdkError
import com.firebox.client.model.FireBoxStreamEvent
import com.firebox.client.model.FireBoxUsage
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatMessage
import com.firebox.core.ChatStreamEvent as CoreChatStreamEvent
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingRequest
import com.firebox.core.EmbeddingResult
import com.firebox.core.EmbeddingResponse
import com.firebox.core.FireBoxError
import com.firebox.core.ModelCandidateInfo
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import com.firebox.core.VirtualModelInfo

internal fun FireBoxChatRequest.toCore(): ChatCompletionRequest =
    ChatCompletionRequest(
        virtualModelId = virtualModelId,
        messages = messages.map(FireBoxMessage::toCore),
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
    )

internal fun FireBoxEmbeddingRequest.toCore(): EmbeddingRequest =
    EmbeddingRequest(
        virtualModelId = virtualModelId,
        input = input,
    )

private fun FireBoxMessage.toCore(): ChatMessage =
    ChatMessage(
        role = role,
        content = content,
    )

internal fun VirtualModelInfo.toClient(): FireBoxModelInfo =
    FireBoxModelInfo(
        virtualModelId = virtualModelId,
        strategy = strategy,
        candidates = candidates.map(ModelCandidateInfo::toClient),
        available = available,
    )

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

internal fun CoreChatStreamEvent.toClient(): FireBoxStreamEvent =
    FireBoxStreamEvent(
        requestId = requestId,
        type = type.toClientStreamType(),
        deltaText = deltaText,
        selection = selection?.toClient(),
        usage = usage?.toClient(),
        response = response?.toClient(),
        error = error?.toClient(),
    )

private fun ChatMessage.toClient(): FireBoxMessage =
    FireBoxMessage(
        role = role,
        content = content,
    )

private fun ProviderSelection.toClient(): FireBoxProviderSelection =
    FireBoxProviderSelection(
        providerId = providerId,
        providerType = providerType,
        providerName = providerName,
        modelId = modelId,
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
        else -> FireBoxStreamEvent.Type.ERROR
    }
