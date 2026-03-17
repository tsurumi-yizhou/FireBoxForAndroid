package com.firebox.android.data

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.models.ModelListParams
import com.firebox.android.ai.ProviderBaseUrlNormalizer
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.google.genai.Client
import com.google.genai.types.HttpOptions
import com.google.genai.types.ListModelsConfig
import com.openai.client.okhttp.OpenAIOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.jvm.optionals.getOrNull

class ProviderModelSdkFetcher {
    suspend fun fetchModels(provider: ProviderConfig): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = provider.apiKey.trim()
                require(apiKey.isNotEmpty()) { "API Key 不能为空" }

                val baseUrl = provider.baseUrl.trim()
                val baseUrlPrefix = ProviderBaseUrlNormalizer.providerBaseUrlPrefix(provider.type, baseUrl)

                when (provider.type) {
                    ProviderType.OpenAI -> fetchOpenAiModels(baseUrlPrefix, apiKey)
                    ProviderType.Anthropic -> fetchAnthropicModels(baseUrlPrefix, apiKey)
                    ProviderType.Gemini -> fetchGeminiModels(baseUrlPrefix, apiKey)
                }.distinct().sorted()
            }
        }

    private fun fetchOpenAiModels(
        baseUrlPrefix: String,
        apiKey: String,
    ): List<String> {
        val client =
            OpenAIOkHttpClient
                .builder()
                .apiKey(apiKey)
                .baseUrl(baseUrlPrefix)
                .build()

        val page = client.models().list()
        return page.data().map { it.id() }
    }

    private fun fetchAnthropicModels(
        baseUrlPrefix: String,
        apiKey: String,
    ): List<String> {
        val client =
            AnthropicOkHttpClient
                .builder()
                .apiKey(apiKey)
                .baseUrl(baseUrlPrefix)
                .build()

        val page = client.models().list(ModelListParams.none())
        return page.data().map { it.id() }
    }

    private fun fetchGeminiModels(
        baseUrlPrefix: String,
        apiKey: String,
    ): List<String> {
        val client =
            Client
                .builder()
                .apiKey(apiKey)
                .httpOptions(
                    HttpOptions
                        .builder()
                        .baseUrl(baseUrlPrefix)
                        // apiVersion removed - should be included in baseUrl
                        .build(),
                ).build()

        val response =
            client.models.list(
                ListModelsConfig
                    .builder()
                    .pageSize(200)
                    .build(),
            )

        return response.mapNotNull { model ->
            model.name().getOrNull()
                ?.removePrefix("models/")?.removePrefix("tunedModels/")
        }
    }

}

