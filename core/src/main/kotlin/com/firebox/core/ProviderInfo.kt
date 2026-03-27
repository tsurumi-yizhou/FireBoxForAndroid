package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderInfo(
    val id: Int,
    val providerType: String,
    val name: String,
    val baseUrl: String,
    val enabledModelIds: List<String>,
    val createdAt: String,
    val updatedAt: String,
) : Parcelable
