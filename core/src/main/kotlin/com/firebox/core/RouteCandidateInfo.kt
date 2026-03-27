package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RouteCandidateInfo(
    val providerId: Int,
    val modelId: String,
) : Parcelable
