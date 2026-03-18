package com.firebox.core

import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncResultParcelableTest {
    @Test
    fun chatCompletionResult_roundTripsThroughParcel() {
        val result =
            ChatCompletionResult(
                response =
                    ChatCompletionResponse(
                        virtualModelId = "chat-default",
                        message = ChatMessage(role = "assistant", content = "hello"),
                        selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
                        usage = Usage(10, 20, 30),
                        finishReason = "stop",
                    ),
                error = null,
            )

        val restored = parcelRoundTrip(result)

        assertEquals(result, restored)
        assertNull(restored.error)
    }

    @Test
    fun embeddingResult_roundTripsThroughParcel() {
        val result =
            EmbeddingResult(
                response = null,
                error = FireBoxError(FireBoxError.TIMEOUT, "timed out", "OpenAI", "text-embedding-3-large"),
            )

        val restored = parcelRoundTrip(result)

        assertEquals(result, restored)
        assertNull(restored.response)
    }

    @Test
    fun functionCallResult_roundTripsThroughParcel() {
        val result =
            FunctionCallResult(
                response =
                    FunctionCallResponse(
                        virtualModelId = "chat-default",
                        outputJson = """{"label":"ok"}""",
                        selection = ProviderSelection(1, "OpenAI", "Primary", "gpt-4.1"),
                        usage = Usage(2, 3, 5),
                        finishReason = "stop",
                    ),
                error = null,
            )

        val restored = parcelRoundTrip(result)

        assertEquals(result, restored)
        assertNull(restored.error)
    }

    @Test
    fun virtualModelInfo_roundTripsThroughParcel() {
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

        val restored = parcelRoundTrip(info)

        assertEquals(info, restored)
    }

    private inline fun <reified T : Parcelable> parcelRoundTrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readParcelable(T::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
