package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RouteWriteRequest(
    val id: Int = 0,
    val routeId: String,
    val strategy: String,
    val candidates: List<RouteCandidateInfo>,
    val reasoning: Boolean,
    val toolCalling: Boolean,
    val inputFormatsMask: Int,
    val outputFormatsMask: Int,
) : Parcelable
