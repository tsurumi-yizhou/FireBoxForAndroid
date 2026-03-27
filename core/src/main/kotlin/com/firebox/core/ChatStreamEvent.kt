package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatStreamEvent(
    val requestId: Long,
    val type: Int,
    val deltaText: String?,
    val reasoningText: String? = null,
    val usage: Usage?,
    val modelId: String? = null,
    val message: ChatMessage? = null,
    val finishReason: String? = null,
    val error: String? = null,
) : Parcelable {
    companion object {
        const val STARTED = 0
        const val DELTA = 1
        const val USAGE = 2
        const val COMPLETED = 3
        const val ERROR = 4
        const val CANCELLED = 5
        const val REASONING_DELTA = 6
    }
}
