package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingRequest(
    val modelId: String,
    val input: List<String>,
) : Parcelable
