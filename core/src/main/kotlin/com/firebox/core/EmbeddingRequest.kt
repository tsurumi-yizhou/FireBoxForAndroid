package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingRequest(
    val virtualModelId: String,
    val input: List<String>,
) : Parcelable
