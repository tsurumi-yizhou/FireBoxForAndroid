package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Usage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
) : Parcelable
