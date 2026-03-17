package com.firebox.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.firebox.client.internal.toClient
import com.firebox.client.internal.toCore
import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxChatResult
import com.firebox.client.model.FireBoxEmbeddingRequest
import com.firebox.client.model.FireBoxEmbeddingResult
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxStreamEvent
import com.firebox.core.ChatStreamEvent as CoreChatStreamEvent
import com.firebox.core.IChatStreamCallback
import com.firebox.core.IFireBoxService
import com.firebox.core.IServiceCallback
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn

class FireBoxClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FireBoxClient"
        private const val SERVICE_ACTION = "com.firebox.android.action.BIND_FIREBOX_SERVICE"
        private const val SERVICE_PACKAGE = "com.firebox.android"

        @Volatile
        private var instance: FireBoxClient? = null

        @JvmStatic
        fun getInstance(context: Context): FireBoxClient {
            return instance ?: synchronized(this) {
                instance ?: FireBoxClient(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private var fireBoxService: IFireBoxService? = null
    private var isConnected = false
    private val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()
    private val messageListeners = CopyOnWriteArrayList<MessageListener>()
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serviceCallback = object : IServiceCallback.Stub() {
        override fun onMessage(message: String?) {
            message?.let { msg ->
                messageListeners.forEach { it.onMessage(msg) }
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            connectionListeners.forEach { it.onConnectionStateChanged(connected) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            fireBoxService = IFireBoxService.Stub.asInterface(service)
            isConnected = true
            try {
                fireBoxService?.registerCallback(serviceCallback.asBinder())
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to register callback", e)
            }
            connectionListeners.forEach { it.onConnected() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            fireBoxService = null
            isConnected = false
            connectionListeners.forEach { it.onDisconnected() }
        }
    }

    fun connect(): Boolean {
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return true
        }

        val intent = Intent().apply {
            action = SERVICE_ACTION
            `package` = SERVICE_PACKAGE
        }

        return try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
            false
        }
    }

    fun disconnect() {
        if (!isConnected) return

        try {
            fireBoxService?.unregisterCallback(serviceCallback.asBinder())
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind service", e)
        } finally {
            fireBoxService = null
            isConnected = false
        }
    }

    fun performOperation(): String? {
        return try {
            fireBoxService?.performOperation()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to perform operation", e)
            null
        }
    }

    fun getVersionCode(): Int {
        return try {
            fireBoxService?.versionCode ?: -1
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to get version code", e)
            -1
        }
    }

    fun listModels(): List<FireBoxModelInfo>? {
        return try {
            fireBoxService?.listVirtualModels()?.map { it.toClient() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list models", e)
            null
        }
    }

    fun chatCompletion(req: FireBoxChatRequest): FireBoxChatResult {
        val service = fireBoxService ?: throw IllegalStateException("FireBox service is not connected")
        return try {
            service.chatCompletion(req.toCore()).toClient()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to run chat completion", e)
            throw IllegalStateException("FireBox chat completion transport failed", e)
        }
    }

    fun startChatCompletionStream(
        req: FireBoxChatRequest,
        listener: ChatStreamListener,
    ): Long? {
        val service = fireBoxService ?: return null
        return try {
            service.startChatCompletionStream(
                req.toCore(),
                object : IChatStreamCallback.Stub() {
                    override fun onEvent(event: CoreChatStreamEvent?) {
                        event?.let { listener.onEvent(it.toClient()) }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chat stream", e)
            null
        }
    }

    fun cancelChatCompletion(requestId: Long) {
        try {
            fireBoxService?.cancelChatCompletion(requestId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel chat stream", e)
        }
    }

    fun createEmbeddings(req: FireBoxEmbeddingRequest): FireBoxEmbeddingResult {
        val service = fireBoxService ?: throw IllegalStateException("FireBox service is not connected")
        return try {
            service.createEmbeddings(req.toCore()).toClient()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to create embeddings", e)
            throw IllegalStateException("FireBox embedding transport failed", e)
        }
    }

    fun streamChat(req: FireBoxChatRequest): Flow<FireBoxStreamEvent> =
        callbackFlow {
            val service = fireBoxService
            if (service == null) {
                close(IllegalStateException("FireBox service is not connected"))
                return@callbackFlow
            }

            var requestId = -1L
            val callback =
                object : IChatStreamCallback.Stub() {
                    override fun onEvent(event: CoreChatStreamEvent?) {
                        val safeEvent = event?.toClient() ?: return
                        trySend(safeEvent)
                        if (safeEvent.type == FireBoxStreamEvent.Type.COMPLETED ||
                            safeEvent.type == FireBoxStreamEvent.Type.ERROR ||
                            safeEvent.type == FireBoxStreamEvent.Type.CANCELLED
                        ) {
                            close()
                        }
                    }
                }

            try {
                requestId = service.startChatCompletionStream(req.toCore(), callback)
            } catch (e: Exception) {
                close(e)
                return@callbackFlow
            }

            awaitClose {
                if (requestId >= 0) {
                    runCatching { fireBoxService?.cancelChatCompletion(requestId) }
                }
            }
        }

    fun streamChatAsTwoFlows(req: FireBoxChatRequest): Pair<Flow<String>, Flow<FireBoxStreamEvent>> {
        val shared =
            streamChat(req).shareIn(clientScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)
        val deltaFlow = shared.filter { it.type == FireBoxStreamEvent.Type.DELTA }.mapNotNull { it.deltaText }
        val metaFlow = shared.filter { it.type != FireBoxStreamEvent.Type.DELTA }
        return deltaFlow to metaFlow
    }

    fun isConnected(): Boolean = isConnected

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    fun addMessageListener(listener: MessageListener) {
        messageListeners.add(listener)
    }

    fun removeMessageListener(listener: MessageListener) {
        messageListeners.remove(listener)
    }

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionStateChanged(connected: Boolean) {}
    }

    interface MessageListener {
        fun onMessage(message: String)
    }

    fun interface ChatStreamListener {
        fun onEvent(event: FireBoxStreamEvent)
    }
}
