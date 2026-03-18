package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatCompletionResponse(
    val virtualModelId: String,
    val message: ChatMessage,
    val reasoningText: String? = null,
    val selection: ProviderSelection,
    val usage: Usage,
    val finishReason: String,
) : Parcelable
