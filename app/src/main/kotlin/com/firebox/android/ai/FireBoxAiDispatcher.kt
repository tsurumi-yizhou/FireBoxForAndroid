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
import com.firebox.core.IChatStreamCallback
import com.firebox.core.ModelCandidateInfo
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelMediaFormat
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import com.firebox.core.VirtualModelInfo
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

private const val MAX_CHAT_ATTACHMENT_BYTES = 8L * 1024L * 1024L

internal class FireBoxAiDispatcher(
    private val providerGateway: FireBoxProviderGateway = FireBoxProviderGateway(),
) {
    private val random = Random(System.currentTimeMillis())

    fun listVirtualModels(snapshot: RuntimeSnapshot): List<VirtualModelInfo> =
        snapshot.routesByVirtualModelId.values
            .sortedBy { it.virtualModelId }
            .map { route ->
                val capability = route.capability()
                val candidates =
                    route.candidates.map { target ->
                        val provider = snapshot.providersById[target.providerId]
                        ModelCandidateInfo(
                            providerId = target.providerId,
                            providerType = provider?.type?.displayName.orEmpty(),
                            providerName = provider?.name.orEmpty(),
                            baseUrl = provider?.baseUrl.orEmpty(),
                            modelId = target.modelId,
                            enabledInConfig = provider?.isModelEnabled(target.modelId).orFalse(),
                            capabilitySupported = provider?.type?.let { capability.isSupportedBy(it) }.orFalse(),
                        )
                    }
                VirtualModelInfo(
                    virtualModelId = route.virtualModelId,
                    strategy = route.strategy.displayName,
                    capabilities = route.capabilities.toCore(),
                    candidates = candidates,
                    available = candidates.any { it.enabledInConfig && it.capabilitySupported },
                )
            }

    suspend fun chatCompletion(
        snapshot: RuntimeSnapshot,
        request: ChatCompletionRequest,
    ): ChatCompletionResponse {
        validateChatRequest(request)
        val route = resolveRoute(snapshot, request.virtualModelId)
        val preparedRequest = prepareChatRequest(request, route)
        val resolved =
            executeCandidates(
                route = route,
                snapshot = snapshot,
                capability = ProviderCapability.Chat,
            ) { candidate ->
                val result = providerGateway.chatCompletion(candidate.provider, candidate.modelId, preparedRequest)
                ChatCompletionResponse(
                    virtualModelId = request.virtualModelId,
                    message = ChatMessage(role = "assistant", content = result.messageText),
                    reasoningText = result.reasoningText,
                    selection = candidate.selection,
                    usage = result.usage,
                    finishReason = result.finishReason,
                )
            }
        return resolved
    }

    suspend fun createEmbeddings(
        snapshot: RuntimeSnapshot,
        request: EmbeddingRequest,
    ): EmbeddingResponse {
        validateEmbeddingRequest(request)
        val route = resolveRoute(snapshot, request.virtualModelId)
        return executeCandidates(
            route = route,
            snapshot = snapshot,
            capability = ProviderCapability.Embedding,
        ) { candidate ->
            val result = providerGateway.createEmbeddings(candidate.provider, candidate.modelId, request)
            EmbeddingResponse(
                virtualModelId = request.virtualModelId,
                embeddings = result.embeddings,
                selection = candidate.selection,
                usage = result.usage,
            )
        }
    }

    suspend fun callFunction(
        snapshot: RuntimeSnapshot,
        request: FunctionCallRequest,
    ): FunctionCallResponse {
        validateFunctionCallRequest(request)
        val candidate = resolveQuickToolCandidate(snapshot)
        val result = providerGateway.callFunction(candidate.provider, candidate.modelId, request)
        return FunctionCallResponse(
            virtualModelId = request.virtualModelId,
            outputJson = result.outputJson,
            selection = candidate.selection,
            usage = result.usage,
            finishReason = result.finishReason,
        )
    }

    suspend fun streamChatCompletion(
        snapshot: RuntimeSnapshot,
        requestId: Long,
        request: ChatCompletionRequest,
        callback: IChatStreamCallback,
    ): ChatCompletionResponse? {
        return try {
            validateChatRequest(request)
            val route = resolveRoute(snapshot, request.virtualModelId)
            val preparedRequest = prepareChatRequest(request, route)
            val deltaBatcher = StreamDeltaBatcher { delta ->
                sendEvent(
                    callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.DELTA,
                        deltaText = delta,
                        selection = null,
                        usage = null,
                        response = null,
                        error = null,
                    ),
                )
            }
            val reasoningBatcher = StreamDeltaBatcher { delta ->
                sendEvent(
                    callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.REASONING_DELTA,
                        deltaText = null,
                        reasoningText = delta,
                        selection = null,
                        usage = null,
                        response = null,
                        error = null,
                    ),
                )
            }

            val response =
                executeCandidates(
                    route = route,
                    snapshot = snapshot,
                    capability = ProviderCapability.Chat,
                    onCandidateSelected = { candidate ->
                        sendEvent(
                            callback,
                            ChatStreamEvent(
                                requestId = requestId,
                                type = ChatStreamEvent.STARTED,
                                deltaText = null,
                                selection = candidate.selection,
                                usage = null,
                                response = null,
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
                    ChatCompletionResponse(
                        virtualModelId = request.virtualModelId,
                        message = ChatMessage(role = "assistant", content = result.messageText),
                        reasoningText = result.reasoningText,
                        selection = candidate.selection,
                        usage = result.usage,
                        finishReason = result.finishReason,
                    )
                }

            if (response.usage.totalTokens > 0 || response.usage.promptTokens > 0 || response.usage.completionTokens > 0) {
                sendEvent(
                    callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.USAGE,
                        deltaText = null,
                        selection = null,
                        usage = response.usage,
                        response = null,
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
                    selection = null,
                    usage = null,
                    response = response,
                    error = null,
                ),
            )
            response
        } catch (cancelled: CancellationException) {
            sendTerminalIfPossible(
                callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.CANCELLED,
                        deltaText = null,
                        reasoningText = null,
                        selection = null,
                        usage = null,
                        response = null,
                        error = FireBoxError(FireBoxError.CANCELLED, cancelled.message ?: "������ȡ��", null, null),
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
                        selection = null,
                        usage = null,
                        response = null,
                        error = serviceException.error,
                ),
            )
            null
        } catch (remote: RemoteException) {
            throw CancellationException("�ͻ��˻ص��ѶϿ�", remote)
        } catch (other: Throwable) {
            sendTerminalIfPossible(
                callback,
                    ChatStreamEvent(
                        requestId = requestId,
                        type = ChatStreamEvent.ERROR,
                        deltaText = null,
                        reasoningText = null,
                        selection = null,
                        usage = null,
                        response = null,
                        error = FireBoxError(FireBoxError.INTERNAL, other.message ?: "�ڲ�����", null, null),
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
                    message = "û�п��ú�ѡģ��",
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
                    message = "���к�ѡģ������ʧ����",
                    providerType = null,
                    providerModelId = null,
                ),
            )
    }

    private fun resolveRoute(
        snapshot: RuntimeSnapshot,
        virtualModelId: String,
    ): RouteRule =
        snapshot.routesByVirtualModelId[virtualModelId]
            ?: throw FireBoxServiceException(
                FireBoxError(
                    code = FireBoxError.NO_ROUTE,
                    message = "未找到虚拟模型路由：$virtualModelId",
                    providerType = null,
                    providerModelId = null,
                ),
            )

    private fun resolveQuickToolCandidate(snapshot: RuntimeSnapshot): ResolvedCandidate {
        val quickTool = snapshot.quickToolSelection
        if (quickTool.providerId <= 0 || quickTool.modelId.isBlank()) {
            throw noQuickToolCandidate("Quick tool model is not configured")
        }
        val provider =
            snapshot.providersById[quickTool.providerId]
                ?: throw noQuickToolCandidate("快速工具模型对应的供应商不存在")
        if (!provider.isModelEnabled(quickTool.modelId)) {
            throw noQuickToolCandidate("快速工具模型未启用或缺少 API Key")
        }
        return ResolvedCandidate(
            provider = provider,
            modelId = quickTool.modelId,
            selection =
                ProviderSelection(
                    providerId = provider.id,
                    providerType = provider.type.displayName,
                    providerName = provider.name,
                    modelId = quickTool.modelId,
                ),
        )
    }

    private fun resolveCandidates(
        snapshot: RuntimeSnapshot,
        route: RouteRule,
        capability: ProviderCapability,
    ): List<ResolvedCandidate> =
        route.candidates.mapNotNull { target ->
            val provider = snapshot.providersById[target.providerId] ?: return@mapNotNull null
            if (!provider.isModelEnabled(target.modelId)) return@mapNotNull null
            if (!capability.isSupportedBy(provider.type)) return@mapNotNull null
            ResolvedCandidate(
                provider = provider,
                modelId = target.modelId,
                selection =
                    ProviderSelection(
                        providerId = provider.id,
                        providerType = provider.type.displayName,
                        providerName = provider.name,
                        modelId = target.modelId,
                    ),
            )
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
        if (request.virtualModelId.isBlank()) {
            throw invalidArgument("virtualModelId 不能为空")
        }
        if (request.messages.isEmpty()) {
            throw invalidArgument("messages 不能为空")
        }
        request.messages.forEach { message -> validateChatMessage(message) }
    }

    private fun validateChatMessage(message: ChatMessage) {
        if (message.role !in setOf("system", "user", "assistant")) {
            throw invalidArgument("不支持的消息角色�?{message.role}")
        }
        message.attachments.forEach { attachment ->
            if (attachment.mimeType.isBlank()) {
                throw invalidArgument("attachment mimeType 不能为空")
            }
        }
    }

    private fun validateEmbeddingRequest(request: EmbeddingRequest) {
        if (request.virtualModelId.isBlank()) {
            throw invalidArgument("virtualModelId 不能为空")
        }
        if (request.input.isEmpty()) {
            throw invalidArgument("input 不能为空")
        }
        val totalChars = request.input.sumOf { it.length.toLong() }
        if (totalChars > MAX_EMBEDDING_CHARACTERS) {
            throw invalidArgument("embedding 输入过大，可能超�?Binder 限制")
        }
    }

    private fun validateFunctionCallRequest(request: FunctionCallRequest) {
        if (request.virtualModelId.isBlank()) {
            throw invalidArgument("virtualModelId 不能为空")
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

    private fun noQuickToolCandidate(message: String): FireBoxServiceException =
        FireBoxServiceException(
            FireBoxError(
                code = FireBoxError.NO_CANDIDATE,
                message = message,
                providerType = null,
                providerModelId = null,
            ),
        )

    private fun sendEvent(
        callback: IChatStreamCallback,
        event: ChatStreamEvent,
    ) {
        try {
            callback.onEvent(event)
        } catch (remote: RemoteException) {
            throw remote
        }
    }

    private fun sendTerminalIfPossible(
        callback: IChatStreamCallback,
        event: ChatStreamEvent,
    ) {
        runCatching { callback.onEvent(event) }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    companion object {
        private const val MAX_EMBEDDING_CHARACTERS = 200_000L
    }
}

internal data class RuntimeSnapshot(
    val providersById: Map<Int, ProviderConfig>,
    val routesByVirtualModelId: Map<String, RouteRule>,
    val quickToolSelection: QuickToolSelection = QuickToolSelection(),
)

internal data class QuickToolSelection(
    val providerId: Int = 0,
    val modelId: String = "",
)

private data class ResolvedCandidate(
    val provider: ProviderConfig,
    val modelId: String,
    val selection: ProviderSelection,
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

private fun com.firebox.android.model.RouteMediaFormat.toCore(): ModelMediaFormat =
    when (this) {
        com.firebox.android.model.RouteMediaFormat.Image -> ModelMediaFormat.Image
        com.firebox.android.model.RouteMediaFormat.Video -> ModelMediaFormat.Video
        com.firebox.android.model.RouteMediaFormat.Audio -> ModelMediaFormat.Audio
    }

private fun prepareChatRequest(
    request: ChatCompletionRequest,
    route: RouteRule,
): ProviderChatRequest =
    ProviderChatRequest(
        virtualModelId = request.virtualModelId,
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
    )

private fun prepareChatAttachment(attachment: com.firebox.core.ChatAttachment): ProviderChatAttachment {
    val sizeBytes = attachment.sizeBytes.takeIf { it >= 0L } ?: attachment.fileDescriptor.statSize
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
        android.os.ParcelFileDescriptor.AutoCloseInputStream(attachment.fileDescriptor).use { stream ->
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


