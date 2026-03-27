package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelCapabilities(
    val reasoning: Boolean = false,
    val toolCalling: Boolean = false,
    val inputFormats: List<MediaFormat> = emptyList(),
    val outputFormats: List<MediaFormat> = emptyList(),
) : Parcelable
