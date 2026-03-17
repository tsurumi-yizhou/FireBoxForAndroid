package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingResponse(
    val virtualModelId: String,
    val embeddings: List<Embedding>,
    val selection: ProviderSelection,
    val usage: Usage,
) : Parcelable
