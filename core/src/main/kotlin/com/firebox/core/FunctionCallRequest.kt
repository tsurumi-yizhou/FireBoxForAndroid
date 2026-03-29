package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FunctionCallRequest(
    val modelId: String,
    val functionName: String,
    val functionDescription: String,
    val inputJson: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String,
    val temperature: Float?,
    val maxOutputTokens: Int?,
) : Parcelable
