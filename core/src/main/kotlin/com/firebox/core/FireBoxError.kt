package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FireBoxError(
    val code: Int,
    val message: String,
    val providerType: String?,
    val providerModelId: String?,
) : Parcelable {
    companion object {
        const val SECURITY = 1
        const val INVALID_ARGUMENT = 2
        const val NO_ROUTE = 3
        const val NO_CANDIDATE = 4
        const val PROVIDER_ERROR = 5
        const val TIMEOUT = 6
        const val INTERNAL = 7
        const val CANCELLED = 8
    }
}
