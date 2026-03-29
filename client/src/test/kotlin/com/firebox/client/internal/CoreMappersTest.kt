package com.firebox.client.internal

import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxMediaFormat
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxFunctionSpec
import com.firebox.client.model.FireBoxReasoningEffort
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingResponse
import com.firebox.core.EmbeddingResult
import com.firebox.core.FunctionCallResponse
import com.firebox.core.FunctionCallResult
import com.firebox.core.MediaFormat
import com.firebox.core.ModelCapabilities
import com.firebox.core.ModelInfo
import com.firebox.core.Usage
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
    fun chatRequest_mapsReasoningEffortToCore() {
        val request =
            FireBoxChatRequest(
                modelId = "chat-default",
                messages = listOf(FireBoxMessage(role = "user", content = "hello")),
                temperature = null,
                maxOutputTokens = null,
                reasoningEffort = FireBoxReasoningEffort.High,
            )

        val mapped = request.toCore()

        assertEquals(com.firebox.core.ReasoningEffort.High, mapped.reasoningEffort)
        assertEquals("chat-default", mapped.modelId)
    }

    @Test
    fun chatCompletionResult_mapsStringErrorToClient() {
        val result =
            ChatCompletionResult(
                response = null,
                error = "missing route",
            )

        val mapped = result.toClient()

        assertNull(mapped.response)
        assertEquals("missing route", mapped.error)
    }

    @Test
    fun embeddingResult_mapsResponseToClient() {
        val result =
            EmbeddingResult(
                response =
                    EmbeddingResponse(
                        modelId = "text-embedding-004",
                        embeddings = listOf(Embedding(index = 0, vector = floatArrayOf(1f, 2f))),
                        usage = Usage(5, 0, 5),
                    ),
                error = null,
            )

        val mapped = result.toClient()

        assertNull(mapped.error)
        assertNotNull(mapped.response)
        assertEquals("text-embedding-004", mapped.response?.modelId)
        assertEquals(5L, mapped.response?.usage?.totalTokens)
        assertEquals(2, mapped.response?.embeddings?.first()?.vector?.size)
    }

    @Test
    fun chatCompletionResult_mapsResponseToClient() {
        val result =
            ChatCompletionResult(
                response =
                    ChatCompletionResponse(
                        modelId = "chat-default",
                        message = ChatMessage(role = "assistant", content = "hello"),
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
                name = "extract_user",
                description = "Extract a user profile.",
                inputSerializer = FunctionInput.serializer(),
                outputSerializer = FunctionOutput.serializer(),
                temperature = null,
                maxOutputTokens = null,
            )

        val request = spec.toCore(modelId = "chat-default", input = FunctionInput(prompt = "hello"))

        assertTrue(request.inputJson.contains("\"prompt\":\"hello\""))
        assertTrue(request.inputSchemaJson.contains("\"additionalProperties\":false"))
        assertTrue(request.outputSchemaJson.contains("\"required\":[\"name\",\"age\"]"))
        assertFalse(request.outputSchemaJson.isBlank())
        assertEquals("chat-default", request.modelId)
    }

    @Test
    fun modelInfo_mapsCapabilitiesToClient() {
        val info =
            ModelInfo(
                modelId = "chat-default",
                capabilities =
                    ModelCapabilities(
                        reasoning = true,
                        toolCalling = true,
                        inputFormats = listOf(MediaFormat.Image, MediaFormat.Audio),
                        outputFormats = listOf(MediaFormat.Video),
                    ),
                available = true,
            )

        val mapped = info.toClient()

        assertTrue(mapped.capabilities.reasoning)
        assertTrue(mapped.capabilities.toolCalling)
        assertEquals(listOf(FireBoxMediaFormat.Image, FireBoxMediaFormat.Audio), mapped.capabilities.inputFormats)
        assertEquals(listOf(FireBoxMediaFormat.Video), mapped.capabilities.outputFormats)
    }

    @Test
    fun functionCallResult_mapsTypedResponseToClient() {
        val result =
            FunctionCallResult(
                response =
                    FunctionCallResponse(
                        modelId = "chat-default",
                        outputJson = """{"name":"Ada","age":12}""",
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
                    modelId = "chat-default",
                    outputJson = """{"name":"Ada","age":"bad"}""",
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
