package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatCompletionRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val temperature: Float?,
    val maxOutputTokens: Int?,
    val reasoningEffort: ReasoningEffort? = null,
) : Parcelable
