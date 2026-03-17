package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VirtualModelInfo(
    val virtualModelId: String,
    val strategy: String,
    val candidates: List<ModelCandidateInfo>,
    val available: Boolean,
) : Parcelable
