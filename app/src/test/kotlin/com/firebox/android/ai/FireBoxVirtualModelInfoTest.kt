package com.firebox.android.ai

import com.firebox.android.model.ModelTarget
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.android.model.RouteMediaFormat
import com.firebox.android.model.RouteModelCapabilities
import com.firebox.android.model.RouteRule
import com.firebox.android.model.RouteStrategy
import com.firebox.core.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FireBoxVirtualModelInfoTest {
    @Test
    fun listModels_exposesRouteCapabilities() {
        val dispatcher = FireBoxAiDispatcher()
        val snapshot =
            RuntimeSnapshot(
                providersById =
                    mapOf(
                        1 to
                            ProviderConfig(
                                id = 1,
                                type = ProviderType.OpenAI,
                                name = "OpenAI",
                                baseUrl = "https://example.com",
                                enabled = true,
                                enabledModels = listOf("gpt-4.1"),
                                apiKey = "test-key",
                            ),
                    ),
                routesByVirtualModelId =
                    mapOf(
                        "chat-default" to
                            RouteRule(
                                id = 1,
                                virtualModelId = "chat-default",
                                strategy = RouteStrategy.Failover,
                                candidates = listOf(ModelTarget(providerId = 1, modelId = "gpt-4.1")),
                                capabilities =
                                    RouteModelCapabilities(
                                        reasoning = true,
                                        toolCalling = true,
                                        inputFormats = listOf(RouteMediaFormat.Image, RouteMediaFormat.Audio),
                                        outputFormats = listOf(RouteMediaFormat.Video),
                                    ),
                            ),
                    ),
            )

        val model = dispatcher.listModels(snapshot).single()

        assertTrue(model.capabilities.reasoning)
        assertTrue(model.capabilities.toolCalling)
        assertEquals(listOf(MediaFormat.Image, MediaFormat.Audio), model.capabilities.inputFormats)
        assertEquals(listOf(MediaFormat.Video), model.capabilities.outputFormats)
    }
}
