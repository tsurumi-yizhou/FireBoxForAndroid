package com.firebox.android.ai

import com.firebox.android.model.ProviderType
import java.net.URI

object ProviderBaseUrlNormalizer {
    fun normalizeProviderBaseUrl(
        type: ProviderType,
        input: String,
    ): String {
        val raw = input.trim()
        require(raw.isNotBlank()) { "Base URL 不能为空" }

        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Base URL 必须包含 http:// 或 https:// 协议")
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
    ): String = normalizeProviderBaseUrl(type, configuredBaseUrl)
}
