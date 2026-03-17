package com.firebox.client.internal

import com.firebox.client.model.FireBoxSdkError
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.Embedding
import com.firebox.core.EmbeddingResponse
import com.firebox.core.EmbeddingResult
import com.firebox.core.FireBoxError
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}