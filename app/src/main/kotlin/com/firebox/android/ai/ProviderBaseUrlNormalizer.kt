package com.firebox.android.ai

import com.firebox.android.model.ProviderType
import java.net.URI

object ProviderBaseUrlNormalizer {
    fun defaultBaseUrlPrefix(type: ProviderType): String =
        when (type) {
            ProviderType.OpenAI -> "https://api.openai.com"
            ProviderType.Anthropic -> "https://api.anthropic.com"
            ProviderType.Gemini -> "https://generativelanguage.googleapis.com"
        }

    fun normalizeProviderBaseUrl(
        type: ProviderType,
        input: String,
    ): String {
        var raw = input.trim()
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            raw = "https://$raw"
        }

        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: "https"
        val host = uri.host ?: throw IllegalArgumentException("Base URL 无法解析 host：$input")
        val port = uri.port

        val rawPath = uri.rawPath.orEmpty()
        val segments = rawPath.split('/').filter { it.isNotBlank() }

        val prefixPath =
            if (segments.isEmpty()) {
                ""
            } else {
                "/" + segments.joinToString("/") { it.trim('/') }
            }

        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != -1) {
                append(":")
                append(port)
            }
            append(prefixPath)
        }
    }

    fun providerBaseUrlPrefix(
        type: ProviderType,
        configuredBaseUrl: String,
    ): String =
        if (configuredBaseUrl.isBlank()) {
            defaultBaseUrlPrefix(type)
        } else {
            normalizeProviderBaseUrl(type, configuredBaseUrl)
        }
}