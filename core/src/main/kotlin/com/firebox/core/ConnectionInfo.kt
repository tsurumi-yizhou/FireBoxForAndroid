package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ConnectionInfo(
    val connectionId: Int,
    val processId: Int,
    val processName: String,
    val executablePath: String,
    val connectedAt: String,
    val requestCount: Long,
    val hasActiveStream: Boolean,
) : Parcelable
