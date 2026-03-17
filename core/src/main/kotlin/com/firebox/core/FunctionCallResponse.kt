package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FunctionCallResponse(
    val virtualModelId: String,
    val outputJson: String,
    val selection: ProviderSelection,
    val usage: Usage,
    val finishReason: String,
) : Parcelable
