package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelInfo(
    val modelId: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val available: Boolean,
) : Parcelable
