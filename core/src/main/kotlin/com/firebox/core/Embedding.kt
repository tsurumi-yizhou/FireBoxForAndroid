package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Embedding(
    val index: Int,
    val vector: FloatArray,
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Embedding) return false
        return index == other.index && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * index + vector.contentHashCode()
}
