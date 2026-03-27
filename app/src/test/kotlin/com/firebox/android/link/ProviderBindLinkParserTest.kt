package com.firebox.android.link

import com.firebox.android.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBindLinkParserTest {
    @Test
    fun parse_supportsExplicitProviderFields() {
        val result =
            ProviderBindLinkParser.parse(
                "firebox://provider/bind?type=openai&name=OpenRouter&base_url=https%3A%2F%2Fopenrouter.ai%2Fapi%2Fv1&api_key=sk-test",
            )

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(ProviderType.OpenAI, payload.type)
        assertEquals("OpenRouter", payload.name)
        assertEquals("https://openrouter.ai/api/v1", payload.baseUrl)
        assertEquals("sk-test", payload.apiKey)
    }

    @Test
    fun parse_infersProviderTypeFromBaseUrl() {
        val result =
            ProviderBindLinkParser.parse(
                "firebox://provider/bind?base_url=https%3A%2F%2Fapi.anthropic.com%2Fv1&key=test-key",
            )

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(ProviderType.Anthropic, payload.type)
        assertEquals("Anthropic - api.anthropic.com", payload.name)
        assertEquals("https://api.anthropic.com/v1", payload.baseUrl)
        assertEquals("test-key", payload.apiKey)
    }

    @Test
    fun parse_rejectsUnsupportedLinkShape() {
        val result =
            ProviderBindLinkParser.parse(
                "https://example.com/provider/bind?type=openai&api_key=sk-test",
            )

        assertTrue(result.isFailure)
    }
}
