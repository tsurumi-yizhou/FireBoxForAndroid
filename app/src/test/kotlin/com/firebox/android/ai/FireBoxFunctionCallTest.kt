package com.firebox.android.ai

import com.firebox.android.model.ModelTarget
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.android.model.RouteRule
import com.firebox.android.model.RouteStrategy
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FireBoxFunctionCallTest {
    @Test
    fun callFunction_returnsNoCandidateWhenRouteHasNoEnabledProvider() =
        runBlocking {
            val dispatcher = FireBoxAiDispatcher()
            val snapshot =
                RuntimeSnapshot(
                    providersById =
                        mapOf(
                            2 to
                                ProviderConfig(
                                    id = 2,
                                    type = ProviderType.Gemini,
                                    name = "Gemini",
                                    baseUrl = "https://example.com",
                                    enabled = true,
                                    enabledModels = emptyList(),
                                    apiKey = "",
                                ),
                        ),
                    routesByVirtualModelId =
                        mapOf(
                            "chat-default" to
                                RouteRule(
                                    id = 1,
                                    virtualModelId = "chat-default",
                                    strategy = RouteStrategy.Failover,
                                    candidates = listOf(ModelTarget(providerId = 2, modelId = "gemini-2.5-pro")),
                                ),
                        ),
                )

            try {
                dispatcher.callFunction(snapshot, functionRequest())
            } catch (error: FireBoxServiceException) {
                assertEquals(FireBoxError.NO_CANDIDATE, error.error.code)
                return@runBlocking
            }

            throw AssertionError("Expected FireBoxServiceException")
        }

    @Test
    fun buildOpenAiFunctionCallBody_includesStructuredOutputFields() {
        val gateway = FireBoxProviderGateway()
        val provider =
            ProviderConfig(
                id = 1,
                type = ProviderType.OpenAI,
                name = "OpenAI",
                baseUrl = "https://api.openai.com",
                enabled = true,
                enabledModels = listOf("gpt-4.1"),
                apiKey = "test-key",
            )

        val body = gateway.buildOpenAiFunctionCallBody(provider, "gpt-4.1", functionRequest())

        assertEquals("gpt-4.1", body["model"]?.jsonPrimitive?.content)
        assertEquals("json_schema", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "extract_user",
            body["response_format"]?.jsonObject?.get("json_schema")?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
        assertEquals(
            "Build a user summary.",
            body["response_format"]?.jsonObject?.get("json_schema")?.jsonObject?.get("description")?.jsonPrimitive?.content,
        )
        assertEquals(
            "false",
            body["response_format"]?.jsonObject?.get("json_schema")?.jsonObject?.get("schema")?.jsonObject
                ?.get("additionalProperties")?.jsonPrimitive?.content,
        )
        val userPrompt = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(userPrompt.contains("Input JSON"))
        assertTrue(userPrompt.contains("\"prompt\":\"hello\""))
    }

    private fun functionRequest() =
        FunctionCallRequest(
            modelId = "chat-default",
            functionName = "extract_user",
            functionDescription = "Build a user summary.",
            inputJson = """{"prompt":"hello"}""",
            inputSchemaJson = """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"],"additionalProperties":false}""",
            outputSchemaJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}""",
            temperature = 0f,
            maxOutputTokens = 200,
        )
}
