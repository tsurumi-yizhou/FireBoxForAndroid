package com.firebox.android.ai

import android.os.RemoteException
import android.os.SystemClock
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.android.model.RouteRule
import com.firebox.android.model.RouteStrategy
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatMessage
import com.firebox.core.ChatStreamEvent
import com.firebox.core.EmbeddingRequest
import com.firebox.core.EmbeddingResponse
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallRequest
import com.firebox.core.FunctionCallResponse
import com.firebox.core.IChatStreamSink
import com.firebox.core.MediaFormat
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelInfo
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

private const val MAX_CHAT_ATTACHMENT_BYTES = 8L * 1024L * 1024L

internal class FireBoxAiDispatcher(
    private val providerGateway: FireBoxProviderGateway = FireBoxProviderGateway(),
) {
    private val random = Random(System.currentTimeMillis())

    fun listModels(snapshot: RuntimeSnapshot): List<ModelInfo> =
        snapshot.routesByVirtualModelId.values
            .sortedBy { it.virtualModelId }
            .map { route ->
                val capability = route.capability()
                val available =
                    route.candidates.any { target ->
                        val provider = snapshot.providersById[target.providerId] ?: return@any false
                        provider.isModelEnabled(target.modelId) && capability.isSupportedBy(provider.type)
                    }
                ModelInfo(
                    modelId = route.virtualModelId,
                    capabilities = route.capabilities.toCore(),
                    available = available,
                )
            }

    suspend fun chatCompletion(
        snapshot: RuntimeSnapshot,
        request: ChatCompletionRequest,
    ): ExecutedResponse<ChatCompletionResponse> {
        validateChatRequest(request)
        val route = resolveRoute(snapshot, request.modelId)
        val preparedRequest = prepareChatRequest(request, route)
        return executeCandidates(
            route = route,
            snapshot = snapshot,
            capability = ProviderCapability.Chat,
        ) { candidate ->
            val result = providerGateway.chatCompletion(candidate.provider, candidate.modelId, preparedRequest)
            ExecutedResponse(
                response =
                    ChatCompletionResponse(
                        modelId = request.modelId,
                        message = ChatMessage(role = "assistant", content = result.messageText),
                        reasoningText = result.reasoningText,
                        usage = result.usage,
                        finishReason = result.finishReason,
                    ),
                providerType = candidate.provider.type,
                providerModelId = candidate.modelId,
            )
        }
    }

    suspend fun createEmbeddings(
        snapshot: RuntimeSnapshot,
        request: EmbeddingRequest,
    ): ExecutedResponse<EmbeddingResponse> {
        validateEmbeddingRequest(request)
        val route = resolveRoute(snapshot, request.modelId)
        return executeCandidates(
            route = route,
            snapshot = snapshot,
            capability = ProviderCapability.Embedding,
        ) { candidate ->
            val result = providerGateway.createEmbeddings(candidate.provider, candidate.modelId, request)
            ExecutedResponse(
                response =
                    EmbeddingResponse(
                        modelId = request.modelId,
                        embeddings = result.embeddings,
                        usage = result.usage,
                    ),
                providerType = candidate.provider.type,
                providerModelId = candidate.modelId,
            )
        }
    }

    suspend fun callFunction(
        snapshot: RuntimeSnapshot,
        request: FunctionCallRequest,
    ): ExecutedResponse<FunctionCallResponse> {
        validateFunctionCallRequest(request)
        val route = resolveRoute(snapshot, request.modelId)
        return executeCandidates(
            route = route,
            snapshot = snapshot,
            capability = ProviderCapability.FunctionCall,
        ) { candidate ->
            val result = providerGateway.callFunction(candidate.provider, candidate.modelId, request)
            ExecutedResponse(
                response =
                    FunctionCallResponse(
                        modelId = request.modelId,
                        outputJson = result.outputJson,
                        usage = result.usage,
                        finishReason = result.finishReason,
                    ),
                providerType = candidate.provider.type,
                providerModelId = candidate.modelId,
            )
        }
    }

    suspend fun streamChatCompletion(
        snapshot: RuntimeSnapshot,
        requestId: Long,
        request: ChatCompletionRequest,
        callback: IChatStreamSink,
    ): ExecutedResponse<ChatCompletionResponse>? {
        return try {
            validateChatRequest(request)
            val route = resolveRoute(snapshot, request.modelId)
            val preparedRequest = prepareChatRequest(request, route)
            val deltaBatcher =
                StreamDeltaBatcher { delta ->
                    sendEvent(
                        callback,
                        ChatStreamEvent(
                            requestId = requestId,
                            type = ChatStreamEvent.DELTA,
                            deltaText = delta,
                            reasoningText = null,
                            usage = null,
                            modelId = null,
                            message = null,
                            finishReason = null,
                            error = null,
                        ),
                    )
                }
            val reasoningBatcher =
                StreamDeltaBatcher { delta ->
                    sendEvent(
                        callback,
                        ChatStreamEvent(
                            requestId = requestId,
                            type = ChatStreamEvent.REASONING_DELTA,
                            deltaText = null,
                            reasoningText = delta,
                            usage = null,
                            modelId = null,
                            message = null,
                            finishReason = null,
                            error = null,
                        ),
                    )
                }

            val response =
                executeCandidates(
                    route = route,
                    snapshot = snapshot,
                    capability = ProviderCapability.Chat,
                    onCandidateSelected = {
                        sendEvent(
                            callback,
                            ChatStreamEvent(
                                requestId = requestId,
                                type = ChatStreamEvent.STARTED,
                                deltaText = null,
                                reasoningText = null,
                                usage = null,
                                modelId = null,
                                message = null,
                                finishReason = null,
                                error = null,
                            ),
                        )
                    },
                ) { candidate ->
                    val result =
                        providerGateway.streamChatCompletion(candidate.provider, candidate.modelId, preparedRequest) { delta ->
                            deltaBatcher.append(delta.text)
                            reasoningBatcher.append(delta.reasoning)
                        }
                    deltaBatcher.flush()
                    reasoningBatcher.flush()
                    ExecutedResponse(
                        response =
                            ChatCompletionResponse(
                                modelId = request.modelId,
                                message = ChatMessage(role = "assistant", content = result.messageText),
                                reasoningText = result.reasoningText,
                                usage = result.usage,
                                finishReason = result.finishReason,
                            ),
                        providerType = candidate.provider.type,
                        providerModelId = candidate.modelId,
                    )
                }

            val finalResponse = response.response
            if (finalResponse.usage.totalTokens > 0 || finalResponse.usage.promptTokens > 0 || finalResponse.usage.completionTokens > 0) {
                sendEvent(
                    callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.USAGE,
                        deltaText = null,
                        reasoningText = null,
                        usage = finalResponse.usage,
                        modelId = null,
                        message = null,
                        finishReason = null,
                        error = null,
                    ),
                )
            }
            sendEvent(
                callback,
                ChatStreamEvent(
                    requestId = requestId,
                    type = ChatStreamEvent.COMPLETED,
                    deltaText = null,
                    reasoningText = finalResponse.reasoningText,
                    usage = finalResponse.usage,
                    modelId = finalResponse.modelId,
                    message = finalResponse.message,
                    finishReason = finalResponse.finishReason,
                    error = null,
                ),
            )
            response
        } catch (_: CancellationException) {
            sendTerminalIfPossible(
                callback,
                ChatStreamEvent(
                    requestId = requestId,
                    type = ChatStreamEvent.CANCELLED,
                    deltaText = null,
                    reasoningText = null,
                    usage = null,
                    modelId = null,
                    message = null,
                    finishReason = null,
                    error = null,
                ),
            )
            null
        } catch (serviceException: FireBoxServiceException) {
            sendTerminalIfPossible(
                callback,
                ChatStreamEvent(
                    requestId = requestId,
                    type = ChatStreamEvent.ERROR,
                    deltaText = null,
                    reasoningText = null,
                    usage = null,
                    modelId = null,
                    message = null,
                    finishReason = null,
                    error = serviceException.error.message,
                ),
            )
            null
        } catch (remote: RemoteException) {
            throw CancellationException("客户端回调已断开", remote)
        } catch (other: Throwable) {
            sendTerminalIfPossible(
                callback,
                ChatStreamEvent(
                    requestId = requestId,
                    type = ChatStreamEvent.ERROR,
                    deltaText = null,
                    reasoningText = null,
                    usage = null,
                    modelId = null,
                    message = null,
                    finishReason = null,
                    error = other.message ?: "内部错误",
                ),
            )
            null
        }
    }

    private suspend fun <T> executeCandidates(
        route: RouteRule,
        snapshot: RuntimeSnapshot,
        capability: ProviderCapability,
        onCandidateSelected: (suspend (ResolvedCandidate) -> Unit)? = null,
        block: suspend (ResolvedCandidate) -> T,
    ): T {
        val candidates = resolveCandidates(snapshot, route, capability)
        if (candidates.isEmpty()) {
            throw FireBoxServiceException(
                FireBoxError(
                    code = FireBoxError.NO_CANDIDATE,
                    message = "没有可用候选模型",
                    providerType = null,
                    providerModelId = null,
                ),
            )
        }

        var lastProviderError: FireBoxServiceException? = null
        for (candidate in orderedCandidates(route.strategy, candidates)) {
            try {
                onCandidateSelected?.invoke(candidate)
                return block(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (serviceException: FireBoxServiceException) {
                if (serviceException.error.code == FireBoxError.PROVIDER_ERROR || serviceException.error.code == FireBoxError.TIMEOUT) {
                    lastProviderError = serviceException
                    continue
                }
                throw serviceException
            }
        }

        throw lastProviderError
            ?: FireBoxServiceException(
                FireBoxError(
                    code = FireBoxError.PROVIDER_ERROR,
                    message = "所有候选模型调用均失败",
                    providerType = null,
                    providerModelId = null,
                ),
            )
    }

    private fun resolveRoute(
        snapshot: RuntimeSnapshot,
        modelId: String,
    ): RouteRule =
        snapshot.routesByVirtualModelId[modelId]
            ?: throw FireBoxServiceException(
                FireBoxError(
                    code = FireBoxError.NO_ROUTE,
                    message = "未找到模型路由：$modelId",
                    providerType = null,
                    providerModelId = null,
                ),
            )

    private fun resolveCandidates(
        snapshot: RuntimeSnapshot,
        route: RouteRule,
        capability: ProviderCapability,
    ): List<ResolvedCandidate> =
        route.candidates.mapNotNull { target ->
            val provider = snapshot.providersById[target.providerId] ?: return@mapNotNull null
            if (!provider.isModelEnabled(target.modelId)) return@mapNotNull null
            if (!capability.isSupportedBy(provider.type)) return@mapNotNull null
            ResolvedCandidate(provider = provider, modelId = target.modelId)
        }

    private fun orderedCandidates(
        strategy: RouteStrategy,
        candidates: List<ResolvedCandidate>,
    ): List<ResolvedCandidate> =
        when (strategy) {
            RouteStrategy.Failover -> candidates
            RouteStrategy.Random -> candidates.shuffled(random)
        }

    private fun validateChatRequest(request: ChatCompletionRequest) {
        if (request.modelId.isBlank()) {
            throw invalidArgument("modelId 不能为空")
        }
        if (request.messages.isEmpty()) {
            throw invalidArgument("messages 不能为空")
        }
        validateGenerationParameters(
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
        )
        request.messages.forEach { message -> validateChatMessage(message) }
    }

    private fun validateChatMessage(message: ChatMessage) {
        if (message.role !in setOf("system", "user", "assistant")) {
            throw invalidArgument("不支持的消息角色：${message.role}")
        }
        message.attachments.forEach { attachment ->
            if (attachment.mimeType.isBlank()) {
                throw invalidArgument("attachment mimeType 不能为空")
            }
        }
    }

    private fun validateEmbeddingRequest(request: EmbeddingRequest) {
        if (request.modelId.isBlank()) {
            throw invalidArgument("modelId 不能为空")
        }
        if (request.input.isEmpty()) {
            throw invalidArgument("input 不能为空")
        }
        val totalChars = request.input.sumOf { it.length.toLong() }
        if (totalChars > MAX_EMBEDDING_CHARACTERS) {
            throw invalidArgument("embedding 输入过大，可能超出 Binder 限制")
        }
    }

    private fun validateFunctionCallRequest(request: FunctionCallRequest) {
        if (request.modelId.isBlank()) {
            throw invalidArgument("modelId 不能为空")
        }
        if (request.functionName.isBlank()) {
            throw invalidArgument("functionName 不能为空")
        }
        if (request.inputJson.isBlank()) {
            throw invalidArgument("inputJson 不能为空")
        }
        if (request.inputSchemaJson.isBlank()) {
            throw invalidArgument("inputSchemaJson 不能为空")
        }
        if (request.outputSchemaJson.isBlank()) {
            throw invalidArgument("outputSchemaJson 不能为空")
        }
        validateGenerationParameters(
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
        )
    }

    private fun validateGenerationParameters(
        temperature: Float?,
        maxOutputTokens: Int?,
    ) {
        if (temperature != null && temperature < 0f) {
            throw invalidArgument("temperature 必须省略或大于等于 0")
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw invalidArgument("maxOutputTokens 必须省略或大于 0")
        }
    }

    private fun invalidArgument(message: String): FireBoxServiceException =
        FireBoxServiceException(
            FireBoxError(
                code = FireBoxError.INVALID_ARGUMENT,
                message = message,
                providerType = null,
                providerModelId = null,
            ),
        )

    private fun sendEvent(
        callback: IChatStreamSink,
        event: ChatStreamEvent,
    ) {
        try {
            callback.OnEvent(event)
        } catch (remote: RemoteException) {
            throw remote
        }
    }

    private fun sendTerminalIfPossible(
        callback: IChatStreamSink,
        event: ChatStreamEvent,
    ) {
        runCatching { callback.OnEvent(event) }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    companion object {
        private const val MAX_EMBEDDING_CHARACTERS = 200_000L
    }
}

internal data class RuntimeSnapshot(
    val providersById: Map<Int, ProviderConfig>,
    val routesByVirtualModelId: Map<String, RouteRule>,
)

private data class ResolvedCandidate(
    val provider: ProviderConfig,
    val modelId: String,
)

internal data class ExecutedResponse<T>(
    val response: T,
    val providerType: ProviderType,
    val providerModelId: String,
)

private fun RouteRule.capability(): ProviderCapability =
    if (
        virtualModelId.contains("embedding", ignoreCase = true) ||
        candidates.any {
            it.modelId.contains("embedding", ignoreCase = true) ||
                it.model.orEmpty().contains("embedding", ignoreCase = true)
        }
    ) {
        ProviderCapability.Embedding
    } else {
        ProviderCapability.Chat
    }

private enum class ProviderCapability {
    Chat,
    Embedding,
    FunctionCall,
    ;

    fun isSupportedBy(type: ProviderType): Boolean =
        when (this) {
            Chat -> true
            Embedding -> type == ProviderType.OpenAI || type == ProviderType.Gemini
            FunctionCall -> true
        }
}

private fun ProviderConfig.isModelEnabled(modelId: String): Boolean =
    apiKey.isNotBlank() && enabledModels.contains(modelId)

private fun com.firebox.android.model.RouteModelCapabilities.toCore(): ModelCapabilities =
    ModelCapabilities(
        reasoning = reasoning,
        toolCalling = toolCalling,
        inputFormats = inputFormats.map(com.firebox.android.model.RouteMediaFormat::toCore),
        outputFormats = outputFormats.map(com.firebox.android.model.RouteMediaFormat::toCore),
    )

private fun com.firebox.android.model.RouteMediaFormat.toCore(): MediaFormat =
    when (this) {
        com.firebox.android.model.RouteMediaFormat.Image -> MediaFormat.Image
        com.firebox.android.model.RouteMediaFormat.Video -> MediaFormat.Video
        com.firebox.android.model.RouteMediaFormat.Audio -> MediaFormat.Audio
    }

private fun prepareChatRequest(
    request: ChatCompletionRequest,
    route: RouteRule,
): ProviderChatRequest =
    ProviderChatRequest(
        modelId = request.modelId,
        messages =
            request.messages.map { message ->
                ProviderChatMessage(
                    role = message.role,
                    content = message.content,
                    attachments = message.attachments.map(::prepareChatAttachment),
                )
            },
        temperature = request.temperature,
        maxOutputTokens = request.maxOutputTokens,
        reasoningEnabled = route.capabilities.reasoning,
        reasoningEffort = request.reasoningEffort,
    )

private fun prepareChatAttachment(attachment: com.firebox.core.ChatAttachment): ProviderChatAttachment {
    val sizeBytes = attachment.sizeBytes.takeIf { it >= 0L } ?: attachment.data.statSize
    if (sizeBytes > MAX_CHAT_ATTACHMENT_BYTES) {
        throw FireBoxServiceException(
            FireBoxError(
                code = FireBoxError.INVALID_ARGUMENT,
                message = "attachment 过大，超出单文件限制",
                providerType = null,
                providerModelId = null,
            ),
        )
    }
    val bytes =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(attachment.data).use { stream ->
            stream.readBytes()
        }
    if (bytes.size.toLong() > MAX_CHAT_ATTACHMENT_BYTES) {
        throw FireBoxServiceException(
            FireBoxError(
                code = FireBoxError.INVALID_ARGUMENT,
                message = "attachment 过大，超出单文件限制",
                providerType = null,
                providerModelId = null,
            ),
        )
    }
    return ProviderChatAttachment(
        mediaFormat = attachment.mediaFormat,
        mimeType = attachment.mimeType,
        fileName = attachment.fileName,
        base64Data = Base64.getEncoder().encodeToString(bytes),
    )
}

private class StreamDeltaBatcher(
    private val maxDelayMs: Long = 40L,
    private val maxChars: Int = 96,
    private val send: suspend (String) -> Unit,
) {
    private val buffer = StringBuilder()
    private var lastFlushAtMs = SystemClock.elapsedRealtime()

    suspend fun append(delta: String) {
        if (delta.isEmpty()) return
        buffer.append(delta)
        val now = SystemClock.elapsedRealtime()
        if (buffer.length >= maxChars || now - lastFlushAtMs >= maxDelayMs) {
            flush()
        }
    }

    suspend fun flush() {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        lastFlushAtMs = SystemClock.elapsedRealtime()
        send(text)
    }
}
