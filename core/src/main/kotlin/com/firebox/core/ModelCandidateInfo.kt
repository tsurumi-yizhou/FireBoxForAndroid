package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelCandidateInfo(
    val providerId: Int,
    val providerType: String,
    val providerName: String,
    val baseUrl: String,
    val modelId: String,
    val enabledInConfig: Boolean,
    val capabilitySupported: Boolean,
) : Parcelable
