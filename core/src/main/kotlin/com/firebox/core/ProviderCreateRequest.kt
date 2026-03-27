package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderCreateRequest(
    val providerType: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
) : Parcelable
