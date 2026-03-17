package com.firebox.android

import com.firebox.android.model.ClientConnectionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionStateHolder {
    private data class ClientConnectionState(
        val callingUid: Int,
        val packageName: String,
        val connectedAtMs: Long,
        val requestCount: Long,
        val hasActiveStream: Boolean,
        val callbackCount: Int,
    )

    private val lock = Any()
    private val activeStreamCounts = mutableMapOf<Int, Int>()
    private val statesByUid = linkedMapOf<Int, ClientConnectionState>()
    private val _connections = MutableStateFlow<List<ClientConnectionInfo>>(emptyList())

    val connections: StateFlow<List<ClientConnectionInfo>> = _connections.asStateFlow()

    fun onClientConnected(uid: Int, packageName: String) {
        synchronized(lock) {
            val current = statesByUid[uid]
            val updated =
                ClientConnectionState(
                    callingUid = uid,
                    packageName = packageName.ifBlank { current?.packageName.orEmpty() },
                    connectedAtMs = current?.connectedAtMs ?: System.currentTimeMillis(),
                    requestCount = current?.requestCount ?: 0L,
                    hasActiveStream = current?.hasActiveStream ?: false,
                    callbackCount = (current?.callbackCount ?: 0) + 1,
                )
            statesByUid[uid] = updated
            publishLocked()
        }
    }

    fun onClientDisconnected(uid: Int) {
        synchronized(lock) {
            val current = statesByUid[uid] ?: return
            val callbackCount = (current.callbackCount - 1).coerceAtLeast(0)
            if (callbackCount == 0 && !current.hasActiveStream) {
                statesByUid.remove(uid)
            } else {
                statesByUid[uid] = current.copy(callbackCount = callbackCount)
            }
            publishLocked()
        }
    }

    fun onRequestMade(uid: Int, packageName: String) {
        synchronized(lock) {
            val current = statesByUid[uid]
            val updated =
                ClientConnectionState(
                    callingUid = uid,
                    packageName = packageName.ifBlank { current?.packageName.orEmpty() },
                    connectedAtMs = current?.connectedAtMs ?: System.currentTimeMillis(),
                    requestCount = (current?.requestCount ?: 0L) + 1L,
                    hasActiveStream = current?.hasActiveStream ?: false,
                    callbackCount = current?.callbackCount ?: 0,
                )
            statesByUid[uid] = updated
            publishLocked()
        }
    }

    fun onStreamStateChanged(uid: Int, active: Boolean) {
        synchronized(lock) {
            val current = statesByUid[uid]
            if (current == null && !active) return

            val activeCount =
                if (active) {
                    (activeStreamCounts[uid] ?: 0) + 1
                } else {
                    ((activeStreamCounts[uid] ?: 0) - 1).coerceAtLeast(0)
                }

            if (activeCount == 0) {
                activeStreamCounts.remove(uid)
            } else {
                activeStreamCounts[uid] = activeCount
            }

            val hasActiveStream = activeCount > 0
            val updatedCurrent =
                current ?: ClientConnectionState(
                    callingUid = uid,
                    packageName = "",
                    connectedAtMs = System.currentTimeMillis(),
                    requestCount = 0L,
                    hasActiveStream = hasActiveStream,
                    callbackCount = 0,
                )

            if (!hasActiveStream && updatedCurrent.callbackCount == 0) {
                statesByUid.remove(uid)
            } else {
                statesByUid[uid] = updatedCurrent.copy(hasActiveStream = hasActiveStream)
            }
            publishLocked()
        }
    }

    private fun publishLocked() {
        _connections.value =
            statesByUid.values
                .map { state ->
                    ClientConnectionInfo(
                        callingUid = state.callingUid,
                        packageName = state.packageName,
                        connectedAtMs = state.connectedAtMs,
                        requestCount = state.requestCount,
                        hasActiveStream = state.hasActiveStream,
                        callbackCount = state.callbackCount,
                    )
                }
                .sortedByDescending { it.connectedAtMs }
    }
}
