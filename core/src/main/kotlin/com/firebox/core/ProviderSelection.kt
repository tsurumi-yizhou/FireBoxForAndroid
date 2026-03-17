package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderSelection(
    val providerId: Int,
    val providerType: String,
    val providerName: String,
    val modelId: String,
) : Parcelable
