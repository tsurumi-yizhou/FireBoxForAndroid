package com.firebox.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FunctionCallResult(
    val response: FunctionCallResponse?,
    val error: FireBoxError?,
) : Parcelable {
    init {
        require((response == null) != (error == null)) {
            "Exactly one of response or error must be non-null"
        }
    }
}
