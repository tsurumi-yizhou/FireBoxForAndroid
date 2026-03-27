package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatCompletionResponse(
    val modelId: String,
    val message: ChatMessage,
    val reasoningText: String? = null,
    val usage: Usage,
    val finishReason: String,
) : Parcelable
