package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RouteInfo(
    val id: Int,
    val routeId: String,
    val strategy: String,
    val candidates: List<RouteCandidateInfo>,
    val reasoning: Boolean,
    val toolCalling: Boolean,
    val inputFormatsMask: Int,
    val outputFormatsMask: Int,
    val createdAt: String,
    val updatedAt: String,
) : Parcelable
