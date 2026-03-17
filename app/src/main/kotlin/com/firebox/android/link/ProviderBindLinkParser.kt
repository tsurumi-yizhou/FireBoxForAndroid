package com.firebox.android.link

import com.firebox.android.ai.ProviderBaseUrlNormalizer
import com.firebox.android.model.ProviderType
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ProviderBindLinkPayload(
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
)

data class PendingProviderBindRequest(
    val requestId: Long = System.nanoTime(),
    val payload: ProviderBindLinkPayload,
)

object ProviderBindLinkParser {
    private const val BindScheme = "firebox"
    private const val BindHost = "provider"
    private const val BindPath = "/bind"

    fun parse(uriString: String): Result<ProviderBindLinkPayload> =
        runCatching {
            val uri = URI(uriString.trim())
            require(uri.scheme?.equals(BindScheme, ignoreCase = true) == true)
            require(uri.host?.equals(BindHost, ignoreCase = true) == true)
            require(normalizePath(uri.path) == BindPath)

            val params = parseQueryParameters(uri.rawQuery)
            val rawBaseUrl = firstNonBlank(params, "base_url", "baseUrl", "url").orEmpty().trim()
            val type = parseProviderType(
                rawType = firstNonBlank(params, "type", "provider", "provider_type"),
                rawBaseUrl = rawBaseUrl,
            )
            val normalizedBaseUrl =
                if (rawBaseUrl.isBlank()) {
                    ""
                } else {
                    runCatching {
                        ProviderBaseUrlNormalizer.normalizeProviderBaseUrl(type, rawBaseUrl)
                    }.getOrElse { rawBaseUrl }
                }
            val apiKey = firstNonBlank(params, "api_key", "apiKey", "key").orEmpty().trim()
            val name =
                firstNonBlank(params, "name", "provider_name")
                    .orEmpty()
                    .trim()
                    .ifBlank { defaultProviderName(type, normalizedBaseUrl) }

            require(name.isNotBlank())
            require(apiKey.isNotBlank() || normalizedBaseUrl.isNotBlank())

            ProviderBindLinkPayload(
                type = type,
                name = name,
                baseUrl = normalizedBaseUrl,
                apiKey = apiKey,
            )
        }

    private fun parseProviderType(
        rawType: String?,
        rawBaseUrl: String,
    ): ProviderType {
        val normalizedType = rawType?.trim().orEmpty()
        if (normalizedType.isNotBlank()) {
            return when (normalizedType.lowercase()) {
                "openai" -> ProviderType.OpenAI
                "anthropic" -> ProviderType.Anthropic
                "gemini", "google", "googleai", "google-ai" -> ProviderType.Gemini
                else -> throw IllegalArgumentException("Unsupported provider type")
            }
        }

        return inferProviderTypeFromBaseUrl(rawBaseUrl)
            ?: throw IllegalArgumentException("Missing provider type")
    }

    private fun inferProviderTypeFromBaseUrl(rawBaseUrl: String): ProviderType? {
        val normalized = rawBaseUrl.trim().lowercase()
        if (normalized.isBlank()) {
            return null
        }

        return when {
            normalized.contains("anthropic") -> ProviderType.Anthropic
            normalized.contains("generativelanguage.googleapis.com") || normalized.contains("googleapis.com") -> ProviderType.Gemini
            else -> ProviderType.OpenAI
        }
    }

    private fun defaultProviderName(
        type: ProviderType,
        baseUrl: String,
    ): String {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("")
        return if (host.isBlank()) {
            type.displayName
        } else {
            "${type.displayName} - $host"
        }
    }

    private fun normalizePath(path: String?): String =
        path.orEmpty().let { safePath ->
            when {
                safePath.isBlank() -> ""
                safePath.length > 1 -> safePath.trimEnd('/')
                else -> safePath
            }
        }

    private fun parseQueryParameters(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }

        return buildMap {
            rawQuery.split('&')
                .filter { it.isNotBlank() }
                .forEach { pair ->
                    val separatorIndex = pair.indexOf('=')
                    val rawKey = if (separatorIndex >= 0) pair.substring(0, separatorIndex) else pair
                    val rawValue = if (separatorIndex >= 0) pair.substring(separatorIndex + 1) else ""
                    val key = decodeComponent(rawKey)
                    val value = decodeComponent(rawValue)
                    put(key, getOrDefault(key, emptyList()) + value)
                }
        }
    }

    private fun firstNonBlank(
        parameters: Map<String, List<String>>,
        vararg names: String,
    ): String? =
        names.firstNotNullOfOrNull { name ->
            parameters[name]
                ?.firstOrNull { it.isNotBlank() }
        }

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}
