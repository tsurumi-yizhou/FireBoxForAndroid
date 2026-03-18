package com.firebox.core

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatAttachment(
    val mediaFormat: ModelMediaFormat,
    val mimeType: String,
    val fileName: String? = null,
    val fileDescriptor: ParcelFileDescriptor,
    val sizeBytes: Long = -1L,
) : Parcelable
