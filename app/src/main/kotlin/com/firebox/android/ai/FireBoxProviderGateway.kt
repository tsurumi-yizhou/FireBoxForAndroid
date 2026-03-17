package com.firebox.android.ai

import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatMessage
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingRequest
import com.firebox.core.FireBoxError
import com.firebox.core.Usage
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
        request: ChatCompletionRequest,
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
        request: ChatCompletionRequest,
        onDelta: suspend (String) -> Unit,
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

    private fun openAiChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
    ): ProviderChatResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val body =
            buildJsonObject {
                put("model", modelId)
                putJsonArray("messages") {
                    request.messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            },
                        )
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
        val messageText = extractOpenAiMessageText(choice)
        return ProviderChatResult(
            messageText = messageText,
            usage = root.usage(),
            finishReason = choice.string("finish_reason").orEmpty(),
        )
    }

    private suspend fun openAiStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
        onDelta: suspend (String) -> Unit,
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
                        add(
                            buildJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            },
                        )
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
        var usage = Usage(0, 0, 0)
        var finishReason = ""
        httpResponse.use { response ->
            readServerSentEvents(response) { _, data ->
                if (data == "[DONE]") {
                    return@readServerSentEvents
                }
                val root = parseJsonObject(data, provider, modelId)
                root["usage"]?.jsonObject?.let { usage = it.usage() }
                val choice = root.array("choices").firstObjectOrNull() ?: return@readServerSentEvents
                val delta = choice.objectOrNull("delta")?.string("content").orEmpty()
                if (delta.isNotEmpty()) {
                    fullText.append(delta)
                    onDelta(delta)
                }
                finishReason = choice.string("finish_reason").orEmpty().ifBlank { finishReason }
            }
        }
        return ProviderChatResult(fullText.toString(), usage, finishReason)
    }

    private fun anthropicChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
    ): ProviderChatResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val body = anthropicChatBody(modelId, request, stream = false)
        val response = postJson(
            client = requestClient,
            url = "${baseUrl.trimEnd('/')}/messages",
            body = body,
            headers = anthropicHeaders(provider),
            provider = provider,
            modelId = modelId,
        )

        val root = parseJsonObject(response, provider, modelId)
        val messageText = root.array("content").joinTextParts()
        val usageObject = root.objectOrNull("usage")
        val promptTokens = usageObject?.long("input_tokens") ?: 0L
        val completionTokens = usageObject?.long("output_tokens") ?: 0L
        return ProviderChatResult(
            messageText = messageText,
            usage = Usage(promptTokens, completionTokens, promptTokens + completionTokens),
            finishReason = root.string("stop_reason").orEmpty(),
        )
    }

    private suspend fun anthropicStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
        onDelta: suspend (String) -> Unit,
    ): ProviderChatResult {
        val baseUrl = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, provider.baseUrl)
        val response = streamPostJson(
            url = "${baseUrl.trimEnd('/')}/messages",
            body = anthropicChatBody(modelId, request, stream = true),
            headers = anthropicHeaders(provider),
            provider = provider,
            modelId = modelId,
        )

        val fullText = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L
        var finishReason = ""
        response.use { httpResponse ->
            readServerSentEvents(httpResponse) { eventName, data ->
                if (data == "[DONE]") {
                    return@readServerSentEvents
                }
                val root = parseJsonObject(data, provider, modelId)
                when (eventName) {
                    "message_start" -> {
                        val usageObject = root.objectOrNull("message")?.objectOrNull("usage")
                        promptTokens = usageObject?.long("input_tokens") ?: promptTokens
                        completionTokens = usageObject?.long("output_tokens") ?: completionTokens
                    }

                    "content_block_delta" -> {
                        val delta = root.objectOrNull("delta")?.string("text").orEmpty()
                        if (delta.isNotEmpty()) {
                            fullText.append(delta)
                            onDelta(delta)
                        }
                    }

                    "message_delta" -> {
                        completionTokens = root.objectOrNull("usage")?.long("output_tokens") ?: completionTokens
                        finishReason =
                            root.objectOrNull("delta")?.string("stop_reason").orEmpty().ifBlank { finishReason }
                    }

                    "error" -> {
                        val message = root.objectOrNull("error")?.string("message") ?: "Anthropic 流式请求失败"
                        throw serviceException(FireBoxError.PROVIDER_ERROR, message, provider, modelId)
                    }
                }
            }
        }
        return ProviderChatResult(
            messageText = fullText.toString(),
            usage = Usage(promptTokens, completionTokens, promptTokens + completionTokens),
            finishReason = finishReason,
        )
    }

    private fun geminiChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
    ): ProviderChatResult {
        val response = postJson(
            client = requestClient,
            url = geminiEndpoint(provider, modelId, "generateContent"),
            body = geminiChatBody(request),
            headers = emptyMap(),
            provider = provider,
            modelId = modelId,
        )

        val root = parseJsonObject(response, provider, modelId)
        val candidate = root.array("candidates").firstObjectOrNull()
            ?: throw providerPayloadException(provider, modelId, "Gemini 返回缺少 candidates")
        return ProviderChatResult(
            messageText = candidate.objectOrNull("content").extractGeminiText(),
            usage = root.objectOrNull("usageMetadata").usageFromGemini(),
            finishReason = candidate.string("finishReason").orEmpty(),
        )
    }

    private suspend fun geminiStreamChatCompletion(
        provider: ProviderConfig,
        modelId: String,
        request: ChatCompletionRequest,
        onDelta: suspend (String) -> Unit,
    ): ProviderChatResult {
        val response = streamPostJson(
            url = geminiEndpoint(provider, modelId, "streamGenerateContent", alt = "sse"),
            body = geminiChatBody(request),
            headers = emptyMap(),
            provider = provider,
            modelId = modelId,
        )

        val fullText = StringBuilder()
        var usage = Usage(0, 0, 0)
        var finishReason = ""
        response.use { httpResponse ->
            readServerSentEvents(httpResponse) { _, data ->
                if (data == "[DONE]") {
                    return@readServerSentEvents
                }
                val root = parseJsonObject(data, provider, modelId)
                usage = root.objectOrNull("usageMetadata").usageFromGemini().ifNonZeroOrElse(usage)
                val candidate = root.array("candidates").firstObjectOrNull() ?: return@readServerSentEvents
                val candidateText = candidate.objectOrNull("content").extractGeminiText()
                val delta = if (candidateText.startsWith(fullText.toString())) {
                    candidateText.removePrefix(fullText.toString())
                } else {
                    candidateText
                }
                if (delta.isNotEmpty()) {
                    fullText.append(delta)
                    onDelta(delta)
                }
                finishReason = candidate.string("finishReason").orEmpty().ifBlank { finishReason }
            }
        }
        return ProviderChatResult(fullText.toString(), usage, finishReason)
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

    private fun anthropicChatBody(
        modelId: String,
        request: ChatCompletionRequest,
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
            if (systemText.isNotBlank()) {
                put("system", systemText)
            }
            putJsonArray("messages") {
                conversation.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        },
                    )
                }
            }
        }
    }

    private fun geminiChatBody(request: ChatCompletionRequest): JsonObject {
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
                    add(
                        buildJsonObject {
                            put("role", if (message.role == "assistant") "model" else "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", message.content) })
                            }
                        },
                    )
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

    private fun JsonObject?.extractGeminiText(): String {
        if (this == null) return ""
        return array("parts").joinTextParts()
    }

    private fun JsonArray.joinTextParts(): String =
        joinToString(separator = "") { element ->
            val objectValue = element as? JsonObject ?: return@joinToString ""
            objectValue.string("text").orEmpty()
        }

    private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name]?.jsonObject

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonArray.firstObjectOrNull(): JsonObject? = firstOrNull()?.jsonObject

    private fun JsonArray.toFloatArray(): FloatArray =
        FloatArray(size) { index -> this[index].jsonPrimitive.floatOrNull ?: 0f }

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
}

internal data class ProviderChatResult(
    val messageText: String,
    val usage: Usage,
    val finishReason: String,
)

internal data class ProviderEmbeddingResult(
    val embeddings: List<Embedding>,
    val usage: Usage,
)

internal class FireBoxServiceException(
    val error: FireBoxError,
) : Exception(error.message)