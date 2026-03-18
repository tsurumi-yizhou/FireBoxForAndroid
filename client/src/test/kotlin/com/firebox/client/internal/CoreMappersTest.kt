package com.firebox.client.internal

import com.firebox.client.model.FireBoxFunctionSpec
import com.firebox.client.model.FireBoxSdkError
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingResponse
import com.firebox.core.EmbeddingResult
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallResponse
import com.firebox.core.FunctionCallResult
import com.firebox.core.ModelCandidateInfo
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelMediaFormat
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import com.firebox.core.VirtualModelInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMappersTest {
    @Test
    fun chatCompletionResult_mapsStructuredErrorToClient() {
        val result =
            ChatCompletionResult(
                response = null,
                error = FireBoxError(FireBoxError.NO_ROUTE, "missing route", "OpenAI", "gpt-4.1"),
            )

        val mapped = result.toClient()

        assertNull(mapped.response)
        assertNotNull(mapped.error)
        assertEquals(FireBoxSdkError.NO_ROUTE, mapped.error?.code)
        assertEquals("missing route", mapped.error?.message)
        assertEquals("OpenAI", mapped.error?.providerType)
        assertEquals("gpt-4.1", mapped.error?.providerModelId)
    }

    @Test
    fun embeddingResult_mapsResponseToClient() {
        val result =
            EmbeddingResult(
                response =
                    EmbeddingResponse(
                        virtualModelId = "embedding-default",
                        embeddings = listOf(Embedding(index = 0, vector = floatArrayOf(1f, 2f))),
                        selection = ProviderSelection(2, "Gemini", "Fallback", "text-embedding-004"),
                        usage = Usage(5, 0, 5),
                    ),
                error = null,
            )

        val mapped = result.toClient()

        assertNull(mapped.error)
        assertNotNull(mapped.response)
        assertEquals("embedding-default", mapped.response?.virtualModelId)
        assertEquals("Gemini", mapped.response?.selection?.providerType)
        assertEquals("text-embedding-004", mapped.response?.selection?.modelId)
        assertEquals(5L, mapped.response?.usage?.totalTokens)
        assertEquals(2, mapped.response?.embeddings?.first()?.vector?.size)
    }

    @Test
    fun chatCompletionResult_mapsResponseToClient() {
        val result =
            ChatCompletionResult(
                response =
                    ChatCompletionResponse(
                        virtualModelId = "chat-default",
                        message = ChatMessage(role = "assistant", content = "hello"),
                        selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
                        usage = Usage(3, 7, 10),
                        finishReason = "stop",
                    ),
                error = null,
            )

        val mapped = result.toClient()

        assertNull(mapped.error)
        assertEquals("hello", mapped.response?.message?.content)
        assertEquals("stop", mapped.response?.finishReason)
        assertEquals(10L, mapped.response?.usage?.totalTokens)
    }

    @Test
    fun titleOutputSchema_isValidForOpenAiStructuredOutputs() {
        val schema = FunctionSchemaSupport.schema(TitleTestOutput.serializer())
        // OpenAI Structured Outputs requires: type=object, no $schema/$defs/$id, additionalProperties=false, no nulls
        assertFalse("Schema must not contain \$schema, got: $schema", schema.contains("\"\$schema\""))
        assertFalse("Schema must not contain \$id, got: $schema", schema.contains("\"\$id\""))
        assertFalse("Schema must not contain \$defs, got: $schema", schema.contains("\"\$defs\""))
        assertFalse("Schema must not contain null values, got: $schema", schema.contains(":null"))
        assertTrue("Schema must contain additionalProperties, got: $schema", schema.contains("additionalProperties"))
        assertTrue("Schema must contain type:object, got: $schema", schema.contains("\"type\":\"object\""))
    }

    @Serializable
    private data class TitleTestOutput(val title: String)

    @Test
    fun functionSpec_toCoreGeneratesStrictSchemas() {
        val spec =
            FireBoxFunctionSpec(
                virtualModelId = "chat-default",
                name = "extract_user",
                description = "Extract a user profile.",
                inputSerializer = FunctionInput.serializer(),
                outputSerializer = FunctionOutput.serializer(),
            )

        val request = spec.toCore(FunctionInput(prompt = "hello"))

        assertEquals("chat-default", request.virtualModelId)
        assertTrue(request.inputJson.contains("\"prompt\":\"hello\""))
        assertTrue(request.inputSchemaJson.contains("\"additionalProperties\":false"))
        assertTrue(request.outputSchemaJson.contains("\"required\":[\"name\",\"age\"]"))
        assertFalse(request.outputSchemaJson.isBlank())
    }

    @Test
    fun virtualModelInfo_mapsCapabilitiesToClient() {
        val info =
            VirtualModelInfo(
                virtualModelId = "chat-default",
                strategy = "Failover",
                capabilities =
                    ModelCapabilities(
                        reasoning = true,
                        toolCalling = true,
                        inputFormats = listOf(ModelMediaFormat.Image, ModelMediaFormat.Audio),
                        outputFormats = listOf(ModelMediaFormat.Video),
                    ),
                candidates =
                    listOf(
                        ModelCandidateInfo(
                            providerId = 1,
                            providerType = "OpenAI",
                            providerName = "Primary",
                            baseUrl = "https://example.com",
                            modelId = "gpt-4.1",
                            enabledInConfig = true,
                            capabilitySupported = true,
                        ),
                    ),
                available = true,
            )

        val mapped = info.toClient()

        assertTrue(mapped.capabilities.reasoning)
        assertTrue(mapped.capabilities.toolCalling)
        assertEquals(listOf(com.firebox.client.model.FireBoxMediaFormat.Image, com.firebox.client.model.FireBoxMediaFormat.Audio), mapped.capabilities.inputFormats)
        assertEquals(listOf(com.firebox.client.model.FireBoxMediaFormat.Video), mapped.capabilities.outputFormats)
    }

    @Test
    fun functionCallResult_mapsTypedResponseToClient() {
        val result =
            FunctionCallResult(
                response =
                    FunctionCallResponse(
                        virtualModelId = "chat-default",
                        outputJson = """{"name":"Ada","age":12}""",
                        selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
                        usage = Usage(11, 22, 33),
                        finishReason = "stop",
                    ),
                error = null,
            )

        val mapped = result.toClient(FunctionOutput.serializer())

        assertNull(mapped.error)
        assertEquals("Ada", mapped.response?.output?.name)
        assertEquals(12, mapped.response?.output?.age)
        assertEquals("""{"name":"Ada","age":12}""", mapped.response?.rawJson)
    }

    @Test(expected = SerializationException::class)
    fun functionCallResult_throwsWhenTypedDecodeFails() {
        FunctionCallResult(
            response =
                FunctionCallResponse(
                    virtualModelId = "chat-default",
                    outputJson = """{"name":"Ada","age":"bad"}""",
                    selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
                    usage = Usage(1, 1, 2),
                    finishReason = "stop",
                ),
            error = null,
        ).toClient(FunctionOutput.serializer())
    }

    @Serializable
    private data class FunctionInput(
        val prompt: String,
    )

    @Serializable
    private data class FunctionOutput(
        val name: String,
        val age: Int,
    )
}
