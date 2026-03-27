package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderUpdateRequest(
    val providerId: Int,
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val enabledModelIds: List<String> = emptyList(),
) : Parcelable
