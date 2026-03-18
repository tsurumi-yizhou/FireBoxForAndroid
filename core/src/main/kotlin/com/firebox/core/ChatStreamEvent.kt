package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatStreamEvent(
    val requestId: Long,
    val type: Int,
    val deltaText: String?,
    val reasoningText: String? = null,
    val selection: ProviderSelection?,
    val usage: Usage?,
    val response: ChatCompletionResponse?,
    val error: FireBoxError?,
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
