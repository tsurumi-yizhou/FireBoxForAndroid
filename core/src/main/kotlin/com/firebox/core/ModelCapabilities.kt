package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelCapabilities(
    val reasoning: Boolean = false,
    val toolCalling: Boolean = false,
    val inputFormats: List<ModelMediaFormat> = emptyList(),
    val outputFormats: List<ModelMediaFormat> = emptyList(),
) : Parcelable
