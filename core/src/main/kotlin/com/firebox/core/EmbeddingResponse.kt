package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingResponse(
    val modelId: String,
    val embeddings: List<Embedding>,
    val usage: Usage,
) : Parcelable
