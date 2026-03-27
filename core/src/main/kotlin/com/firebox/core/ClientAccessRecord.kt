package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ClientAccessRecord(
    val id: Int,
    val processId: Int,
    val processName: String,
    val executablePath: String,
    val requestCount: Long,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val isAllowed: Boolean,
    val deniedUntilUtc: String? = null,
) : Parcelable
