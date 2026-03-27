package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatCompletionRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val temperature: Float = -1f,
    val maxOutputTokens: Int = -1,
    val reasoningEffort: ReasoningEffort? = null,
) : Parcelable
