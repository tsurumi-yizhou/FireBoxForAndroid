package com.firebox.android

import com.firebox.android.ai.FireBoxServiceException
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatMessage
import com.firebox.core.FireBoxError
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FireBoxServiceSyncContractTest {
    @Test
    fun fireBoxSyncResultOf_returnsResponseOnSuccess() =
        runBlocking {
            val response =
                ChatCompletionResponse(
                    virtualModelId = "chat-default",
                    message = ChatMessage(role = "assistant", content = "ok"),
                    selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
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
        assertFailureCode(SecurityException("missing permission"), FireBoxError.SECURITY)

    @Test
    fun fireBoxSyncResultOf_mapsIllegalArgumentException() =
        assertFailureCode(IllegalArgumentException("bad request"), FireBoxError.INVALID_ARGUMENT)

    @Test
    fun fireBoxSyncResultOf_mapsNoRouteError() =
        assertServiceFailureCode(FireBoxError.NO_ROUTE)

    @Test
    fun fireBoxSyncResultOf_mapsNoCandidateError() =
        assertServiceFailureCode(FireBoxError.NO_CANDIDATE)

    @Test
    fun fireBoxSyncResultOf_mapsTimeoutError() =
        assertServiceFailureCode(FireBoxError.TIMEOUT)

    @Test
    fun fireBoxSyncResultOf_mapsProviderError() =
        assertServiceFailureCode(FireBoxError.PROVIDER_ERROR)

    @Test
    fun fireBoxSyncResultOf_mapsUnknownThrowableToInternal() =
        assertFailureCode(IllegalStateException("boom"), FireBoxError.INTERNAL)

    private fun assertServiceFailureCode(code: Int) =
        assertFailureCode(
            FireBoxServiceException(
                FireBoxError(
                    code = code,
                    message = "service error",
                    providerType = "OpenAI",
                    providerModelId = "gpt-4.1",
                ),
            ),
            code,
        )

    private fun assertFailureCode(
        throwable: Throwable,
        expectedCode: Int,
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
            assertNotNull(result.error)
            assertEquals(expectedCode, result.error?.code)
        }
}