package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FunctionCallRequest(
    val functionName: String,
    val functionDescription: String,
    val inputJson: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String,
    val temperature: Float = 0f,
    val maxOutputTokens: Int = -1,
) : Parcelable
