package com.firebox.android

import com.firebox.android.ai.FireBoxServiceException
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.FireBoxError
import com.firebox.core.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FireBoxServiceSyncContractTest {
    @Test
    fun fireBoxSyncResultOf_returnsResponseOnSuccess() =
        runBlocking {
            val response =
                ChatCompletionResponse(
                    modelId = "chat-default",
                    message = ChatMessage(role = "assistant", content = "ok"),
                    usage = Usage(1, 2, 3),
                    finishReason = "stop",
                )

            val result =
                fireBoxSyncResultOf(
                    success = { ChatCompletionResult(response = it, error = null) },
                    failure = { ChatCompletionResult(response = null, error = it) },
                ) {
                    response
                }

            assertEquals(response, result.response)
            assertNull(result.error)
        }

    @Test
    fun fireBoxSyncResultOf_mapsSecurityException() =
        assertFailureMessage(SecurityException("missing permission"), "missing permission")

    @Test
    fun fireBoxSyncResultOf_mapsIllegalArgumentException() =
        assertFailureMessage(IllegalArgumentException("bad request"), "bad request")

    @Test
    fun fireBoxSyncResultOf_mapsServiceExceptionMessage() =
        assertFailureMessage(
            FireBoxServiceException(
                FireBoxError(
                    code = FireBoxError.NO_ROUTE,
                    message = "service error",
                    providerType = "OpenAI",
                    providerModelId = "gpt-4.1",
                ),
            ),
            "service error",
        )

    @Test
    fun fireBoxSyncResultOf_mapsUnknownThrowableToInternalMessage() =
        assertFailureMessage(IllegalStateException("boom"), "boom")

    private fun assertFailureMessage(
        throwable: Throwable,
        expectedMessage: String,
    ) =
        runBlocking {
            val result =
                fireBoxSyncResultOf(
                    success = { ChatCompletionResult(response = it, error = null) },
                    failure = { ChatCompletionResult(response = null, error = it) },
                ) {
                    throw throwable
                }

            assertNull(result.response)
            assertEquals(expectedMessage, result.error)
        }
}
