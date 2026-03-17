package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingResult(
    val response: EmbeddingResponse?,
    val error: FireBoxError?,
) : Parcelable {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}