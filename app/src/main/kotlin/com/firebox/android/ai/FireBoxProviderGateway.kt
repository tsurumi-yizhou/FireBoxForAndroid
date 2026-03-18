package com.firebox.android.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonField
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageDeltaEvent
import com.anthropic.models.messages.RawMessageStartEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.core.ChatMessage
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingRequest
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallRequest
import com.firebox.core.ModelMediaFormat
import com.firebox.core.Usage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.google.genai.Client
import com.google.genai.ResponseStream
import com.google.genai.types.Content
import com.google.genai.types.FunctionCallingConfig
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.GenerateContentResponseUsageMetadata
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ToolConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class FireBoxProviderGateway {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jacksonMapper = JsonMapper.builder().findAndAddModules().build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val requestClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    private val streamClient =
        requestClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    suspend fun chatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
    ): ProviderChatResult =
        withContext(Dispatchers.IO) {
            when (provider.type) {
                ProviderType.OpenAI -> openAiChatCompletion(provider, modelId, request)
                ProviderType.Anthropic -> anthropicChatCompletion(provider, modelId, request)
                ProviderType.Gemini -> geminiChatCompletion(provider, modelId, request)
            }
        }

    suspend fun streamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
        onDelta: suspend (ProviderChatDelta) -> Unit,
    ): ProviderChatResult =
        withContext(Dispatchers.IO) {
            when (provider.type) {
                ProviderType.OpenAI -> openAiStreamChatCompletion(provider, modelId, request, onDelta)
                ProviderType.Anthropic -> anthropicStreamChatCompletion(provider, modelId, request, onDelta)
                ProviderType.Gemini -> geminiStreamChatCompletion(provider, modelId, request, onDelta)
            }
        }

    suspend fun createEmbeddings(
        provider: ProviderConfig,
        modelId: String,
        request: EmbeddingRequest,
    ): ProviderEmbeddingResult =
        withContext(Dispatchers.IO) {
            when (provider.type) {
                ProviderType.OpenAI -> openAiEmbeddings(provider, modelId, request)
                ProviderType.Gemini -> geminiEmbeddings(provider, modelId, request)
                ProviderType.Anthropic -> throw serviceException(
                    code = FireBoxError.INTERNAL,
                    message = "Anthropic 当前不支持 embedding",
                    provider = provider,
                    modelId = modelId,
                )
            }
        }

    suspend fun callFunction(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): ProviderFunctionResult =
        withContext(Dispatchers.IO) {
            when (provider.type) {
                ProviderType.OpenAI -> openAiFunctionCall(provider, modelId, request)
                ProviderType.Anthropic -> anthropicFunctionCall(provider, modelId, request)
                ProviderType.Gemini -> geminiFunctionCall(provider, modelId, request)
            }
        }

    private fun openAiChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
    ): ProviderChatResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val body =
            buildJsonObject {
                put("model", modelId)
                putJsonArray("messages") {
                    request.messages.forEach { message ->
                        add(buildOpenAiMessage(message))
                    }
                }
                if (request.temperature >= 0f) {
                    put("temperature", request.temperature)
                }
                if (request.maxOutputTokens > 0) {
                    put("max_tokens", request.maxOutputTokens)
                }
            }

        val response = postJson(
            client = requestClient,
            url = "${baseUrl.trimEnd('/')}/chat/completions",
            body = body,
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey.trim()}"),
            provider = provider,
            modelId = modelId,
        )

        val root = parseJsonObject(response, provider, modelId)
        val choice = root.array("choices").firstObjectOrNull()
            ?: throw providerPayloadException(provider, modelId, "OpenAI 返回缺少 choices")
        val content = extractOpenAiMessageContent(choice)
        return ProviderChatResult(
            messageText = content.messageText,
            reasoningText = content.reasoningText,
            usage = root.usage(),
            finishReason = choice.string("finish_reason").orEmpty(),
        )
    }

    private suspend fun openAiStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
        onDelta: suspend (ProviderChatDelta) -> Unit,
    ): ProviderChatResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val body =
            buildJsonObject {
                put("model", modelId)
                put("stream", true)
                putJsonObject("stream_options") {
                    put("include_usage", true)
                }
                putJsonArray("messages") {
                    request.messages.forEach { message ->
                        add(buildOpenAiMessage(message))
                    }
                }
                if (request.temperature >= 0f) {
                    put("temperature", request.temperature)
                }
                if (request.maxOutputTokens > 0) {
                    put("max_tokens", request.maxOutputTokens)
                }
            }

        val httpResponse = streamPostJson(
            url = "${baseUrl.trimEnd('/')}/chat/completions",
            body = body,
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey.trim()}"),
            provider = provider,
            modelId = modelId,
        )

        val fullText = StringBuilder()
        val reasoningText = StringBuilder()
        var usage = Usage(0, 0, 0)
        var finishReason = ""
        httpResponse.use { response ->
            readServerSentEvents(response) { _, data ->
                if (data == "[DONE]") {
                    return@readServerSentEvents
                }
                val root = parseJsonObject(data, provider, modelId)
                root["usage"]?.let { it as? JsonObject }?.let { usage = it.usage() }
                val choice = root.array("choices").firstObjectOrNull() ?: return@readServerSentEvents
                val deltaPayload = extractOpenAiDelta(choice.objectOrNull("delta"))
                if (deltaPayload.text.isNotEmpty()) {
                    fullText.append(deltaPayload.text)
                    onDelta(ProviderChatDelta(text = deltaPayload.text))
                }
                if (deltaPayload.reasoning.isNotEmpty()) {
                    reasoningText.append(deltaPayload.reasoning)
                    onDelta(ProviderChatDelta(reasoning = deltaPayload.reasoning))
                }
                finishReason = choice.string("finish_reason").orEmpty().ifBlank { finishReason }
            }
        }
        return ProviderChatResult(
            messageText = fullText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
            usage = usage,
            finishReason = finishReason,
        )
    }

    private fun anthropicChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
    ): ProviderChatResult {
        return withAnthropicClient(provider, modelId) { client ->
            val response = client.messages().create(buildAnthropicChatParams(modelId, request))
            val content = response.content().extractAnthropicSdkContent()
            ProviderChatResult(
                messageText = content.messageText,
                reasoningText = content.reasoningText,
                usage = response.usage().toCoreUsage(),
                finishReason = response.stopReason().map { it.toString() }.orElse(""),
            )
        }
    }

    private suspend fun anthropicStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
        onDelta: suspend (ProviderChatDelta) -> Unit,
    ): ProviderChatResult {
        val fullText = StringBuilder()
        val reasoningText = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L
        var finishReason = ""
        runProviderCallSuspend(provider, modelId) {
            val client = anthropicClient(provider)
            try {
                client.messages().createStreaming(buildAnthropicChatParams(modelId, request)).use { stream ->
                    val iterator = stream.stream().iterator()
                    while (iterator.hasNext()) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val event = iterator.next()
                        when {
                            event.isMessageStart() -> {
                                val usage = event.asMessageStart().message().usage()
                                promptTokens = usage.inputTokens()
                                completionTokens = usage.outputTokens()
                            }

                            event.isContentBlockDelta() -> {
                                val delta = event.asContentBlockDelta().delta()
                                if (delta.isText()) {
                                    val text = delta.asText().text()
                                    if (text.isNotEmpty()) {
                                        fullText.append(text)
                                        onDelta(ProviderChatDelta(text = text))
                                    }
                                }
                                if (delta.isThinking()) {
                                    val thinking = delta.asThinking().thinking()
                                    if (thinking.isNotEmpty()) {
                                        reasoningText.append(thinking)
                                        onDelta(ProviderChatDelta(reasoning = thinking))
                                    }
                                }
                            }

                            event.isMessageDelta() -> {
                                completionTokens = event.asMessageDelta().usage().outputTokens()
                                finishReason = event.asMessageDelta().delta().stopReason().map { it.toString() }.orElse(finishReason)
                            }
                        }
                    }
                }
            } finally {
                client.close()
            }
        }
        return ProviderChatResult(
            messageText = fullText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
            usage = Usage(promptTokens, completionTokens, promptTokens + completionTokens),
            finishReason = finishReason,
        )
    }

    private fun geminiChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
    ): ProviderChatResult {
        return withGeminiClient(provider, modelId) { client ->
            val response =
                client.models.generateContent(
                    modelId,
                    buildGeminiContents(request.messages),
                    buildGeminiChatConfig(request),
                )
            val content = response.extractGeminiSdkContent()
            ProviderChatResult(
                messageText = content.messageText,
                reasoningText = content.reasoningText,
                usage = response.usageMetadata().toCoreUsage(),
                finishReason = response.finishReason().toString(),
            )
        }
    }

    private suspend fun geminiStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ProviderChatRequest,
        onDelta: suspend (ProviderChatDelta) -> Unit,
    ): ProviderChatResult {
        val fullText = StringBuilder()
        val reasoningText = StringBuilder()
        var usage = Usage(0, 0, 0)
        var finishReason = ""
        runProviderCallSuspend(provider, modelId) {
            val client = geminiClient(provider)
            try {
                client.models.generateContentStream(
                    modelId,
                    buildGeminiContents(request.messages),
                    buildGeminiChatConfig(request),
                ).use { stream ->
                    for (chunk in stream) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        usage = chunk.usageMetadata().toCoreUsage().ifNonZeroOrElse(usage)
                        val content = chunk.extractGeminiSdkContent()
                        val delta = content.messageText.deltaFrom(fullText.toString())
                        if (delta.isNotEmpty()) {
                            fullText.append(delta)
                            onDelta(ProviderChatDelta(text = delta))
                        }
                        val reasoningDelta = content.reasoningText.orEmpty().deltaFrom(reasoningText.toString())
                        if (reasoningDelta.isNotEmpty()) {
                            reasoningText.append(reasoningDelta)
                            onDelta(ProviderChatDelta(reasoning = reasoningDelta))
                        }
                        finishReason = chunk.finishReason().toString().takeIf { it.isNotBlank() } ?: finishReason
                    }
                }
            } finally {
                client.close()
            }
        }
        return ProviderChatResult(
            messageText = fullText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
            usage = usage,
            finishReason = finishReason,
        )
    }

    private fun openAiEmbeddings(
        provider: ProviderConfig,
        modelId: String,
        request: EmbeddingRequest,
    ): ProviderEmbeddingResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val response = postJson(
            client = requestClient,
            url = "${baseUrl.trimEnd('/')}/embeddings",
            body =
                buildJsonObject {
                    put("model", modelId)
                    putJsonArray("input") {
                        request.input.forEach { add(JsonPrimitive(it)) }
                    }
                },
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey.trim()}"),
            provider = provider,
            modelId = modelId,
        )

        val root = parseJsonObject(response, provider, modelId)
        val embeddings =
            root.array("data").mapIndexed { index, item ->
                val itemObject = item.jsonObject
                Embedding(
                    index = itemObject.int("index") ?: index,
                    vector = itemObject.array("embedding").toFloatArray(),
                )
            }
        return ProviderEmbeddingResult(embeddings, root.usage())
    }

    private fun geminiEmbeddings(
        provider: ProviderConfig,
        modelId: String,
        request: EmbeddingRequest,
    ): ProviderEmbeddingResult {
        val vectors = ArrayList<Embedding>(request.input.size)
        var promptTokens = 0L
        for ((index, input) in request.input.withIndex()) {
            val response = postJson(
                client = requestClient,
                url = geminiEndpoint(provider, modelId, "embedContent"),
                body =
                    buildJsonObject {
                        putJsonObject("content") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", input) })
                            }
                        }
                    },
                headers = emptyMap(),
                provider = provider,
                modelId = modelId,
            )
            val root = parseJsonObject(response, provider, modelId)
            val values = root.objectOrNull("embedding")?.array("values")?.toFloatArray()
                ?: throw providerPayloadException(provider, modelId, "Gemini 返回缺少 embedding.values")
            vectors += Embedding(index = index, vector = values)
            promptTokens += root.objectOrNull("usageMetadata")?.long("promptTokenCount") ?: 0L
        }
        return ProviderEmbeddingResult(vectors, Usage(promptTokens, 0, promptTokens))
    }

    private fun buildAnthropicChatParams(
        modelId: String,
        request: ProviderChatRequest,
    ): MessageCreateParams {
        val builder =
            MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.maxOutputTokens.takeIf { it > 0 }?.toLong() ?: 1024L)

        if (request.temperature >= 0f) {
            builder.temperature(request.temperature.toDouble())
        }
        if (request.reasoningEnabled) {
            val budget =
                request.maxOutputTokens
                    .takeIf { it > 0 }
                    ?.coerceAtMost(2048)
                    ?.coerceAtLeast(1024)
                    ?: 1024
            builder.enabledThinking(budget.toLong())
        }

        val systemText = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (systemText.isNotBlank()) {
            builder.system(systemText)
        }

        request.messages
            .filter { it.role != "system" }
            .forEach { message ->
                builder.addMessage(message.toAnthropicMessageParam())
            }
        return builder.build()
    }

    private fun buildAnthropicFunctionParams(
        modelId: String,
        request: FunctionCallRequest,
    ): MessageCreateParams {
        val builder =
            MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.maxOutputTokens.takeIf { it > 0 }?.toLong() ?: 1024L)
                .system(
                    "You implement the function ${request.functionName}. " +
                        "Return only valid JSON that matches the output schema exactly. " +
                        "Do not include markdown fences or any extra explanation.",
                )
                .addUserMessage(buildFunctionCallPrompt(request))
        if (request.temperature >= 0f) {
            builder.temperature(request.temperature.toDouble())
        }
        return builder.build()
    }

    private fun ProviderChatMessage.toAnthropicMessageParam(): MessageParam {
        val builder = MessageParam.builder().role(MessageParam.Role.of(role))
        if (attachments.isEmpty()) {
            builder.content(content)
            return builder.build()
        }
        val blocks = buildList {
            if (content.isNotBlank()) {
                add(
                    ContentBlockParam.ofText(
                        TextBlockParam.builder()
                            .text(content)
                            .build(),
                    ),
                )
            }
            attachments.forEach { attachment ->
                require(attachment.mediaFormat == ModelMediaFormat.Image) {
                    "Anthropic SDK 暂仅支持图片输入，收到 ${attachment.mediaFormat}"
                }
                add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.of(attachment.mimeType))
                                    .data(attachment.base64Data)
                                    .build(),
                            )
                            .build(),
                    ),
                )
            }
        }
        return builder.contentOfBlockParams(blocks).build()
    }

    private fun List<ContentBlock>.extractAnthropicSdkContent(): ProviderChatContent {
        val messageText = StringBuilder()
        val reasoningText = StringBuilder()
        forEach { block ->
            when {
                block.isText() -> messageText.append(block.asText().text())
                block.isThinking() -> reasoningText.append(block.asThinking().thinking())
            }
        }
        return ProviderChatContent(
            messageText = messageText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
        )
    }

    private fun buildGeminiContents(messages: List<ProviderChatMessage>): List<Content> =
        messages
            .filter { it.role != "system" }
            .map { message ->
                val parts =
                    buildList {
                        if (message.content.isNotBlank()) {
                            add(Part.fromText(message.content))
                        }
                        message.attachments.forEach { attachment ->
                            require(attachment.mediaFormat == ModelMediaFormat.Image) {
                                "Gemini SDK 暂仅支持图片输入，收到 ${attachment.mediaFormat}"
                            }
                            add(
                                Part.fromBytes(
                                    Base64.getDecoder().decode(attachment.base64Data),
                                    attachment.mimeType,
                                ),
                            )
                        }
                    }
                Content.builder()
                    .role(if (message.role == "assistant") "model" else "user")
                    .parts(parts)
                    .build()
            }

    private fun buildGeminiChatConfig(request: ProviderChatRequest): GenerateContentConfig {
        val builder = GenerateContentConfig.builder()
        if (request.temperature >= 0f) {
            builder.temperature(request.temperature)
        }
        if (request.maxOutputTokens > 0) {
            builder.maxOutputTokens(request.maxOutputTokens)
        }
        if (request.reasoningEnabled) {
            builder.thinkingConfig(
                ThinkingConfig.builder()
                    .includeThoughts(true)
                    .build(),
            )
        }
        val systemText = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (systemText.isNotBlank()) {
            builder.systemInstruction(
                Content.builder()
                    .parts(Part.fromText(systemText))
                    .build(),
            )
        }
        return builder.build()
    }

    private fun buildGeminiFunctionContents(request: FunctionCallRequest): Content =
        Content.builder()
            .role("user")
            .parts(Part.fromText(buildFunctionCallPrompt(request)))
            .build()

    private fun buildGeminiFunctionConfig(request: FunctionCallRequest): GenerateContentConfig {
        val builder =
            GenerateContentConfig.builder()
                .systemInstruction(
                    Content.builder()
                        .parts(
                            Part.fromText(
                                "You implement the function ${request.functionName}. " +
                                    "Return only valid JSON that matches the output schema exactly. " +
                                    "Do not include markdown fences or any extra explanation.",
                            ),
                        )
                        .build(),
                )
                .responseMimeType("application/json")
                .responseJsonSchema(parseSchemaNode(request.outputSchemaJson))
        if (request.temperature >= 0f) {
            builder.temperature(request.temperature)
        }
        if (request.maxOutputTokens > 0) {
            builder.maxOutputTokens(request.maxOutputTokens)
        }
        return builder.build()
    }

    private fun GenerateContentResponse.extractGeminiSdkContent(): ProviderChatContent {
        val messageText = StringBuilder()
        val reasoningText = StringBuilder()
        parts().orEmpty().forEach { part ->
            val text = part.text().orElse("")
            if (text.isBlank()) return@forEach
            if (part.thought().orElse(false)) {
                reasoningText.append(text)
            } else {
                messageText.append(text)
            }
        }
        return ProviderChatContent(
            messageText = messageText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
        )
    }

    private fun <T> withAnthropicClient(
        provider: ProviderConfig,
        modelId: String,
        block: (AnthropicClient) -> T,
    ): T =
        runProviderCall(provider, modelId) {
            val client = anthropicClient(provider)
            try {
                block(client)
            } finally {
                client.close()
            }
        }

    private fun <T> withGeminiClient(
        provider: ProviderConfig,
        modelId: String,
        block: (Client) -> T,
    ): T =
        runProviderCall(provider, modelId) {
            val client = geminiClient(provider)
            try {
                block(client)
            } finally {
                client.close()
            }
        }

    private fun anthropicClient(provider: ProviderConfig): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .apiKey(provider.apiKey.trim())
            .baseUrl(ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl))
            .timeout(Duration.ofSeconds(90))
            .maxRetries(0)
            .build()

    private fun geminiClient(provider: ProviderConfig): Client =
        Client.builder()
            .apiKey(provider.apiKey.trim())
            .httpOptions(
                HttpOptions.builder()
                    .baseUrl(ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl))
                    .apiVersion("v1beta")
                    .timeout(90_000)
                    .build(),
            )
            .build()

    private fun parseSchemaNode(raw: String): JsonNode = jacksonMapper.readTree(raw)

    private fun String.deltaFrom(existing: String): String =
        if (startsWith(existing)) removePrefix(existing) else this

    private fun com.anthropic.models.messages.Usage.toCoreUsage(): Usage =
        Usage(inputTokens(), outputTokens(), inputTokens() + outputTokens())

    private fun java.util.Optional<GenerateContentResponseUsageMetadata>.toCoreUsage(): Usage {
        val metadata = orElse(null)
        val promptTokens = metadata?.promptTokenCount()?.orElse(0) ?: 0
        val completionTokens = metadata?.candidatesTokenCount()?.orElse(0) ?: 0
        val totalTokens = metadata?.totalTokenCount()?.orElse(promptTokens + completionTokens) ?: (promptTokens + completionTokens)
        return Usage(promptTokens.toLong(), completionTokens.toLong(), totalTokens.toLong())
    }

    private fun <T> runProviderCall(
        provider: ProviderConfig,
        modelId: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (service: FireBoxServiceException) {
            throw service
        } catch (timeout: SocketTimeoutException) {
            throw serviceException(FireBoxError.TIMEOUT, timeout.message ?: "请求超时", provider, modelId)
        } catch (other: Throwable) {
            throw serviceException(
                FireBoxError.PROVIDER_ERROR,
                other.rootCauseMessage(),
                provider,
                modelId,
            )
        }

    private suspend fun <T> runProviderCallSuspend(
        provider: ProviderConfig,
        modelId: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (service: FireBoxServiceException) {
            throw service
        } catch (timeout: SocketTimeoutException) {
            throw serviceException(FireBoxError.TIMEOUT, timeout.message ?: "请求超时", provider, modelId)
        } catch (other: Throwable) {
            throw serviceException(
                FireBoxError.PROVIDER_ERROR,
                other.rootCauseMessage(),
                provider,
                modelId,
            )
        }

    private tailrec fun Throwable.rootCauseMessage(): String {
        val next = cause
        return if (next == null || next === this) {
            message ?: this::class.java.simpleName
        } else {
            next.rootCauseMessage()
        }
    }

    private fun anthropicChatBody(
        modelId: String,
        request: ProviderChatRequest,
        stream: Boolean,
    ): JsonObject {
        val systemText = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val conversation = request.messages.filter { it.role != "system" }
        return buildJsonObject {
            put("model", modelId)
            put("max_tokens", request.maxOutputTokens.takeIf { it > 0 } ?: 1024)
            if (request.temperature >= 0f) {
                put("temperature", request.temperature)
            }
            if (stream) {
                put("stream", true)
            }
            if (request.reasoningEnabled) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", request.maxOutputTokens.takeIf { it > 0 }?.coerceAtMost(2048)?.coerceAtLeast(1024) ?: 1024)
                }
            }
            if (systemText.isNotBlank()) {
                put("system", systemText)
            }
            putJsonArray("messages") {
                conversation.forEach { message ->
                    add(buildAnthropicMessage(message))
                }
            }
        }
    }

    private fun openAiFunctionCall(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): ProviderFunctionResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val body = buildOpenAiFunctionCallBody(provider, modelId, request)

        val response = postJson(
            client = requestClient,
            url = "${baseUrl.trimEnd('/')}/chat/completions",
            body = body,
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey.trim()}"),
            provider = provider,
            modelId = modelId,
        )

        val root = parseJsonObject(response, provider, modelId)
        val choice = root.array("choices").firstObjectOrNull()
            ?: throw providerPayloadException(provider, modelId, "OpenAI 返回缺少 choices")
        val message = choice.objectOrNull("message")
            ?: throw providerPayloadException(provider, modelId, "OpenAI 返回缺少 message")
        val refusal = message.string("refusal").orEmpty()
        if (refusal.isNotBlank()) {
            throw serviceException(FireBoxError.PROVIDER_ERROR, refusal, provider, modelId)
        }
        val rawOutput = extractOpenAiMessageText(choice)
        if (rawOutput.isBlank()) {
            throw providerPayloadException(provider, modelId, "OpenAI 返回缺少结构化 JSON")
        }
        val normalizedOutput = normalizeFunctionOutput(rawOutput, provider, modelId)
        return ProviderFunctionResult(
            outputJson = normalizedOutput,
            usage = root.usage(),
            finishReason = choice.string("finish_reason").orEmpty(),
        )
    }

    private fun anthropicFunctionCall(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): ProviderFunctionResult {
        return withAnthropicClient(provider, modelId) { client ->
            val response = client.messages().create(buildAnthropicFunctionParams(modelId, request))
            val content = response.content().extractAnthropicSdkContent()
            if (content.messageText.isBlank()) {
                throw providerPayloadException(provider, modelId, "Anthropic 返回缺少结构化 JSON")
            }
            ProviderFunctionResult(
                outputJson = normalizeFunctionOutput(content.messageText, provider, modelId),
                usage = response.usage().toCoreUsage(),
                finishReason = response.stopReason().map { it.toString() }.orElse(""),
            )
        }
    }

    private fun geminiFunctionCall(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): ProviderFunctionResult {
        return withGeminiClient(provider, modelId) { client ->
            val response =
                client.models.generateContent(
                    modelId,
                    buildGeminiFunctionContents(request),
                    buildGeminiFunctionConfig(request),
                )
            val content = response.extractGeminiSdkContent()
            if (content.messageText.isBlank()) {
                throw providerPayloadException(provider, modelId, "Gemini 返回缺少结构化 JSON")
            }
            ProviderFunctionResult(
                outputJson = normalizeFunctionOutput(content.messageText, provider, modelId),
                usage = response.usageMetadata().toCoreUsage(),
                finishReason = response.finishReason().toString(),
            )
        }
    }

    private fun geminiChatBody(request: ProviderChatRequest): JsonObject {
        val systemText = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val conversation = request.messages.filter { it.role != "system" }
        return buildJsonObject {
            if (systemText.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemText) })
                    }
                }
            }
            putJsonArray("contents") {
                conversation.forEach { message ->
                    add(buildGeminiMessage(message))
                }
            }
            if (request.temperature >= 0f || request.maxOutputTokens > 0) {
                putJsonObject("generationConfig") {
                    if (request.temperature >= 0f) {
                        put("temperature", request.temperature)
                    }
                    if (request.maxOutputTokens > 0) {
                        put("maxOutputTokens", request.maxOutputTokens)
                    }
                }
            }
        }
    }

    private fun geminiEndpoint(
        provider: ProviderConfig,
        modelId: String,
        method: String,
        alt: String? = null,
    ): String {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val queryParameters =
            buildList {
                add("key=${encode(provider.apiKey.trim())}")
                alt?.let { add("alt=${encode(it)}") }
            }.joinToString("&")
        return "${baseUrl.trimEnd('/')}/v1beta/models/${encodePathSegment(modelId)}:$method?$queryParameters"
    }

    private fun anthropicHeaders(provider: ProviderConfig): Map<String, String> =
        mapOf(
            "x-api-key" to provider.apiKey.trim(),
            "anthropic-version" to "2023-06-01",
        )

    private fun postJson(
        client: OkHttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
        provider: ProviderConfig,
        modelId: String,
    ): String {
        val request = requestBuilder(url, headers)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
            .build()

        return executeRequest(client, request, provider, modelId).use { response ->
            response.body.string()
        }
    }

    private fun streamPostJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
        provider: ProviderConfig,
        modelId: String,
    ): Response {
        val request = requestBuilder(url, headers)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
            .build()
        return executeRequest(streamClient, request, provider, modelId)
    }

    private fun requestBuilder(
        url: String,
        headers: Map<String, String>,
    ): Request.Builder {
        val builder = Request.Builder().url(url).header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun executeRequest(
        client: OkHttpClient,
        request: Request,
        provider: ProviderConfig,
        modelId: String,
    ): Response {
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body.string()
                response.close()
                throw httpFailure(provider, modelId, response.code, body)
            }
            return response
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            throw serviceException(FireBoxError.TIMEOUT, timeout.message ?: "请求超时", provider, modelId)
        } catch (io: IOException) {
            throw serviceException(FireBoxError.PROVIDER_ERROR, io.message ?: "网络请求失败", provider, modelId)
        }
    }

    private suspend fun readServerSentEvents(
        response: Response,
        onEvent: suspend (eventName: String?, data: String) -> Unit,
    ) {
        val source = response.body.source()
        var eventName: String? = null
        val dataLines = ArrayList<String>()
        while (!source.exhausted()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) {
                if (dataLines.isNotEmpty()) {
                    onEvent(eventName, dataLines.joinToString("\n"))
                    dataLines.clear()
                    eventName = null
                }
                continue
            }
            when {
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
            }
        }
        if (dataLines.isNotEmpty()) {
            onEvent(eventName, dataLines.joinToString("\n"))
        }
    }

    private fun parseJsonObject(
        raw: String,
        provider: ProviderConfig,
        modelId: String,
    ): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw providerPayloadException(provider, modelId, "响应不是有效 JSON")
        }

    private fun parseJsonElement(
        raw: String,
        provider: ProviderConfig,
        modelId: String,
    ): JsonElement =
        runCatching { json.parseToJsonElement(raw) }.getOrElse {
            throw providerPayloadException(provider, modelId, "响应不是有效 JSON")
        }

    private fun normalizeFunctionOutput(
        raw: String,
        provider: ProviderConfig,
        modelId: String,
    ): String {
        val candidates =
            buildList {
                val trimmed = raw.trim()
                add(trimmed)
                add(trimmed.removeSurrounding("```json", "```").trim())
                add(trimmed.removeSurrounding("```", "```").trim())
                val objectStart = trimmed.indexOf('{')
                val objectEnd = trimmed.lastIndexOf('}')
                if (objectStart >= 0 && objectEnd > objectStart) {
                    add(trimmed.substring(objectStart, objectEnd + 1))
                }
                val arrayStart = trimmed.indexOf('[')
                val arrayEnd = trimmed.lastIndexOf(']')
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    add(trimmed.substring(arrayStart, arrayEnd + 1))
                }
                // Attempt to repair truncated JSON (e.g. {"title": "Some ti)
                if (objectStart >= 0 && objectEnd <= objectStart) {
                    val partial = trimmed.substring(objectStart)
                    add(repairTruncatedJson(partial))
                }
            }
        candidates.forEach { candidate ->
            val parsed = runCatching { parseJsonElement(candidate, provider, modelId) }.getOrNull()
            if (parsed != null) {
                return json.encodeToString(JsonElement.serializer(), parsed)
            }
        }
        throw providerPayloadException(provider, modelId, "响应中未找到有效 JSON")
    }

    private fun repairTruncatedJson(partial: String): String {
        val sb = StringBuilder(partial)
        var inString = false
        var escaped = false
        var braceDepth = 0
        var bracketDepth = 0
        for (ch in partial) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> braceDepth++
                !inString && ch == '}' -> braceDepth--
                !inString && ch == '[' -> bracketDepth++
                !inString && ch == ']' -> bracketDepth--
            }
        }
        if (inString) sb.append('"')
        repeat(bracketDepth) { sb.append(']') }
        repeat(braceDepth) { sb.append('}') }
        return sb.toString()
    }

    private fun extractAnthropicToolUseJson(
        content: JsonArray,
        toolName: String,
        provider: ProviderConfig,
        modelId: String,
    ): String {
        val toolUse =
            content.firstOrNull { element ->
                val item = element as? JsonObject ?: return@firstOrNull false
                item.string("type") == "tool_use" && item.string("name") == toolName
            } as? JsonObject
                ?: throw providerPayloadException(provider, modelId, "Anthropic 未返回预期的 tool_use 结果")
        val input = toolUse["input"]
            ?: throw providerPayloadException(provider, modelId, "Anthropic tool_use 缺少 input")
        return json.encodeToString(JsonElement.serializer(), input)
    }

    private fun extractOpenAiMessageText(choice: JsonObject): String {
        val message = choice.objectOrNull("message") ?: return ""
        return when (val content = message["content"]) {
            null -> ""
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinTextParts()
            else -> ""
        }
    }

    private fun JsonObject.usage(): Usage {
        val promptTokens = long("prompt_tokens") ?: 0L
        val completionTokens = long("completion_tokens") ?: 0L
        val totalTokens = long("total_tokens") ?: (promptTokens + completionTokens)
        return Usage(promptTokens, completionTokens, totalTokens)
    }

    private fun JsonObject?.usageFromGemini(): Usage {
        val promptTokens = this?.long("promptTokenCount") ?: 0L
        val completionTokens = this?.long("candidatesTokenCount") ?: 0L
        val totalTokens = this?.long("totalTokenCount") ?: (promptTokens + completionTokens)
        return Usage(promptTokens, completionTokens, totalTokens)
    }

    private fun Usage.ifNonZeroOrElse(other: Usage): Usage =
        if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) this else other

    private fun JsonObject?.extractGeminiContent(): ProviderChatContent {
        if (this == null) return ProviderChatContent("", null)
        val messageText = StringBuilder()
        val reasoningText = StringBuilder()
        array("parts").forEach { element ->
            val objectValue = element as? JsonObject ?: return@forEach
            val text = objectValue.string("text").orEmpty()
            val isThought = objectValue["thought"]?.jsonPrimitive?.contentOrNull == "true"
            if (text.isBlank()) return@forEach
            if (isThought) {
                reasoningText.append(text)
            } else {
                messageText.append(text)
            }
        }
        return ProviderChatContent(
            messageText = messageText.toString(),
            reasoningText = reasoningText.toString().ifBlank { null },
        )
    }

    private fun JsonArray.joinTextParts(): String =
        joinToString(separator = "") { element ->
            val objectValue = element as? JsonObject ?: return@joinToString ""
            objectValue.string("text").orEmpty()
        }

    private fun JsonArray.extractAnthropicContent(): ProviderChatContent {
        val messageText = StringBuilder()
        val reasoningText = StringBuilder()
        forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            when (item.string("type")) {
                "text" -> messageText.append(item.string("text").orEmpty())
                "thinking", "reasoning" -> reasoningText.append(item.string("thinking").orEmpty().ifBlank { item.string("text").orEmpty() })
            }
        }
        return ProviderChatContent(messageText.toString(), reasoningText.toString().ifBlank { null })
    }

    private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonArray.firstObjectOrNull(): JsonObject? = firstOrNull() as? JsonObject

    private fun JsonArray.toFloatArray(): FloatArray =
        FloatArray(size) { index -> this[index].jsonPrimitive.floatOrNull ?: 0f }

    private fun JsonObject.toGeminiSchema(): JsonObject {
        if (containsKey("\$ref") || containsKey("oneOf") || containsKey("anyOf") || containsKey("allOf")) {
            throw IllegalArgumentException("Gemini responseSchema does not support \$ref/oneOf/anyOf/allOf")
        }
        return buildJsonObject {
            when (val typeValue = this@toGeminiSchema["type"]) {
                is JsonPrimitive -> put("type", typeValue.content.toGeminiType())
                is JsonArray -> {
                    val nonNullType =
                        typeValue.firstOrNull { element ->
                            (element as? JsonPrimitive)?.content != "null"
                        } as? JsonPrimitive
                    if (nonNullType != null) {
                        put("type", nonNullType.content.toGeminiType())
                    }
                    if (typeValue.any { (it as? JsonPrimitive)?.content == "null" }) {
                        put("nullable", true)
                    }
                }
                null -> Unit
                else -> throw IllegalArgumentException("Unsupported Gemini schema type declaration")
            }
            this@toGeminiSchema["description"]?.let { put("description", it) }
            this@toGeminiSchema["enum"]?.let { put("enum", it) }
            this@toGeminiSchema["properties"]?.jsonObject?.takeIf { it.isNotEmpty() }?.let { properties ->
                putJsonObject("properties") {
                    properties.forEach { (name, value) ->
                        put(name, value.jsonObject.toGeminiSchema())
                    }
                }
            }
            this@toGeminiSchema["required"]?.let { put("required", it) }
            this@toGeminiSchema["items"]?.let { items ->
                put("items", items.jsonObject.toGeminiSchema())
            }
        }
    }

    private fun String.toGeminiType(): String =
        when (lowercase()) {
            "string" -> "STRING"
            "number" -> "NUMBER"
            "integer" -> "INTEGER"
            "boolean" -> "BOOLEAN"
            "array" -> "ARRAY"
            "object" -> "OBJECT"
            else -> throw IllegalArgumentException("Unsupported Gemini schema type: $this")
        }

    private fun httpFailure(
        provider: ProviderConfig,
        modelId: String,
        statusCode: Int,
        body: String,
    ): FireBoxServiceException {
        val errorMessage =
            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                root.objectOrNull("error")?.string("message")
                    ?: root.string("message")
                    ?: body.ifBlank { null }
            }.getOrNull() ?: "HTTP $statusCode"
        return serviceException(FireBoxError.PROVIDER_ERROR, errorMessage, provider, modelId)
    }

    private fun providerPayloadException(
        provider: ProviderConfig,
        modelId: String,
        message: String,
    ): FireBoxServiceException = serviceException(FireBoxError.PROVIDER_ERROR, message, provider, modelId)

    private fun serviceException(
        code: Int,
        message: String,
        provider: ProviderConfig,
        modelId: String,
    ): FireBoxServiceException =
        FireBoxServiceException(
            FireBoxError(
                code = code,
                message = message,
                providerType = provider.type.displayName,
                providerModelId = modelId,
            ),
        )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodePathSegment(value: String): String = encode(value).replace("+", "%20")

    private fun buildFunctionCallPrompt(request: FunctionCallRequest): String =
        buildString {
            appendLine("Function name: ${request.functionName}")
            appendLine()
            appendLine("Function description:")
            appendLine(request.functionDescription.ifBlank { "No additional description provided." })
            appendLine()
            appendLine("Input schema:")
            appendLine(request.inputSchemaJson)
            appendLine()
            appendLine("Input JSON:")
            appendLine(request.inputJson)
            appendLine()
            appendLine("Output schema:")
            appendLine(request.outputSchemaJson)
            appendLine()
            append("Return only JSON that matches the response schema exactly.")
        }

    private fun buildAnthropicFunctionCallBody(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
        toolName: String,
    ): JsonObject {
        val outputSchema = parseJsonObject(request.outputSchemaJson, provider, modelId)
        return buildJsonObject {
            put("model", modelId)
            put("max_tokens", request.maxOutputTokens.takeIf { it > 0 } ?: 1024)
            if (request.temperature >= 0f) {
                put("temperature", request.temperature)
            }
            put(
                "system",
                "You implement the function ${request.functionName}. " +
                    "Compute the result and call the provided tool exactly once with the final structured output. " +
                    "Do not answer with plain text.",
            )
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildFunctionCallPrompt(request))
                    },
                )
            }
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("name", toolName)
                        if (request.functionDescription.isNotBlank()) {
                            put("description", request.functionDescription)
                        }
                        put("input_schema", outputSchema)
                    },
                )
            }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", toolName)
            }
        }
    }

    private fun sanitizeSchemaName(value: String): String {
        val sanitized = value.map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }.joinToString("")
        return sanitized.take(64).ifBlank { "firebox_function" }
    }

    internal fun buildOpenAiFunctionCallBody(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): JsonObject {
        val outputSchema = parseJsonObject(request.outputSchemaJson, provider, modelId)
        return buildJsonObject {
            put("model", modelId)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            "You implement the function ${request.functionName}. " +
                                "Return only valid JSON that matches the response schema. " +
                                "Do not wrap the JSON in markdown.",
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildFunctionCallPrompt(request))
                    },
                )
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", sanitizeSchemaName(request.functionName))
                    if (request.functionDescription.isNotBlank()) {
                        put("description", request.functionDescription)
                    }
                    put("strict", true)
                    put("schema", outputSchema)
                }
            }
            if (request.temperature >= 0f) {
                put("temperature", request.temperature)
            }
            if (request.maxOutputTokens > 0) {
                put("max_tokens", request.maxOutputTokens)
            }
        }
    }

    private fun buildGeminiFunctionCallBody(
        provider: ProviderConfig,
        modelId: String,
        request: FunctionCallRequest,
    ): JsonObject {
        val outputSchema = parseJsonObject(request.outputSchemaJson, provider, modelId).toGeminiSchema()
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(
                        buildJsonObject {
                            put(
                                "text",
                                "You implement the function ${request.functionName}. " +
                                    "Return only valid JSON matching the output schema exactly. " +
                                    "Do not include markdown fences or commentary.",
                            )
                        },
                    )
                }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", buildFunctionCallPrompt(request)) })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", outputSchema)
                if (request.temperature >= 0f) {
                    put("temperature", request.temperature)
                }
                if (request.maxOutputTokens > 0) {
                    put("maxOutputTokens", request.maxOutputTokens)
                }
            }
        }
    }

    private fun buildOpenAiMessage(message: ProviderChatMessage): JsonObject =
        buildJsonObject {
            put("role", message.role)
            if (message.attachments.isEmpty()) {
                put("content", message.content)
            } else {
                putJsonArray("content") {
                    if (message.content.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            },
                        )
                    }
                    message.attachments.forEach { attachment ->
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", attachment.dataUrl())
                                }
                            },
                        )
                    }
                }
            }
        }

    private fun buildAnthropicMessage(message: ProviderChatMessage): JsonObject =
        buildJsonObject {
            put("role", message.role)
            if (message.attachments.isEmpty()) {
                put("content", message.content)
            } else {
                putJsonArray("content") {
                    if (message.content.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            },
                        )
                    }
                    message.attachments.forEach { attachment ->
                        add(
                            buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", attachment.mimeType)
                                    put("data", attachment.base64Data)
                                }
                            },
                        )
                    }
                }
            }
        }

    private fun buildGeminiMessage(message: ProviderChatMessage): JsonObject =
        buildJsonObject {
            put("role", if (message.role == "assistant") "model" else "user")
            putJsonArray("parts") {
                if (message.content.isNotBlank()) {
                    add(buildJsonObject { put("text", message.content) })
                }
                message.attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", attachment.mimeType)
                                put("data", attachment.base64Data)
                            }
                        },
                    )
                }
            }
        }

    private fun extractOpenAiMessageContent(choice: JsonObject): ProviderChatContent {
        val message = choice.objectOrNull("message") ?: return ProviderChatContent("", null)
        return extractOpenAiContent(message["content"])
    }

    private fun extractOpenAiDelta(delta: JsonObject?): ProviderChatDelta {
        if (delta == null) return ProviderChatDelta()
        val content = extractOpenAiContent(delta["content"])
        val directReasoning =
            delta.string("reasoning").orEmpty().ifBlank {
                delta.string("reasoning_content").orEmpty()
            }
        return ProviderChatDelta(
            text = content.messageText,
            reasoning = content.reasoningText.orEmpty() + directReasoning,
        )
    }

    private fun extractOpenAiContent(content: JsonElement?): ProviderChatContent =
        when (content) {
            null -> ProviderChatContent("", null)
            is JsonPrimitive -> ProviderChatContent(content.contentOrNull.orEmpty(), null)
            is JsonArray -> {
                val messageText = StringBuilder()
                val reasoningText = StringBuilder()
                content.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    when (item.string("type")) {
                        "text", "output_text" -> {
                            messageText.append(item.string("text").orEmpty().ifBlank { item.string("content").orEmpty() })
                        }

                        "reasoning", "reasoning_text", "thinking" -> {
                            reasoningText.append(
                                item.string("text").orEmpty()
                                    .ifBlank { item.string("reasoning").orEmpty() }
                                    .ifBlank { item.string("content").orEmpty() },
                            )
                        }
                    }
                }
                ProviderChatContent(
                    messageText = messageText.toString(),
                    reasoningText = reasoningText.toString().ifBlank { null },
                )
            }

            else -> ProviderChatContent("", null)
        }
}

internal data class ProviderChatResult(
    val messageText: String,
    val reasoningText: String? = null,
    val usage: Usage,
    val finishReason: String,
)

internal data class ProviderChatDelta(
    val text: String = "",
    val reasoning: String = "",
)

internal data class ProviderChatRequest(
    val virtualModelId: String,
    val messages: List<ProviderChatMessage>,
    val temperature: Float = -1f,
    val maxOutputTokens: Int = -1,
    val reasoningEnabled: Boolean = false,
)

internal data class ProviderChatMessage(
    val role: String,
    val content: String,
    val attachments: List<ProviderChatAttachment> = emptyList(),
)

internal data class ProviderChatAttachment(
    val mediaFormat: ModelMediaFormat,
    val mimeType: String,
    val fileName: String? = null,
    val base64Data: String,
) {
    fun dataUrl(): String = "data:$mimeType;base64,$base64Data"
}

private data class ProviderChatContent(
    val messageText: String,
    val reasoningText: String?,
)

internal data class ProviderEmbeddingResult(
    val embeddings: List<Embedding>,
    val usage: Usage,
)

internal data class ProviderFunctionResult(
    val outputJson: String,
    val usage: Usage,
    val finishReason: String,
)

internal class FireBoxServiceException(
    val error: FireBoxError,
) : Exception(error.message)
