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
import com.firebox.client.model.FireBoxFunctionResult
import com.firebox.client.model.FireBoxFunctionSpec
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxStreamEvent
import com.firebox.core.ChatCompletionRequest as CoreChatCompletionRequest
import com.firebox.core.ChatStreamEvent as CoreChatStreamEvent
import com.firebox.core.ICapabilityService
import com.firebox.core.IChatStreamSink
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
import kotlinx.serialization.serializer

class FireBoxClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FireBoxClient"
        private const val SERVICE_ACTION = "com.firebox.android.action.BIND_CAPABILITY_SERVICE"
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

    private var capabilityService: ICapabilityService? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isBinding = false
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var isDisconnecting = false
    @Volatile
    private var lastConnectionError: String? = null
    private val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            capabilityService = ICapabilityService.Stub.asInterface(service)
            isConnected = true
            isBinding = false
            isReconnecting = false
            lastConnectionError = null
            connectionListeners.forEach { it.onConnected() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            handleConnectionLoss(errorMessage = "Service disconnected", attemptReconnect = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "Service returned null binding")
            handleConnectionLoss(errorMessage = "Service returned null binding", attemptReconnect = false)
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(TAG, "Service binding died")
            runCatching { context.unbindService(this) }
                .onFailure { throwable ->
                    Log.d(TAG, "Ignore unbind failure after binding died", throwable)
                }
            handleConnectionLoss(errorMessage = "Service binding died", attemptReconnect = true)
        }
    }

    fun connect(): Boolean {
        if (isBinding) {
            Log.w(TAG, "Stale bind in progress; resetting connection before rebinding")
            runCatching { context.unbindService(serviceConnection) }
                .onFailure { throwable ->
                    Log.d(TAG, "Ignore stale unbind failure before rebinding", throwable)
                }
            isBinding = false
        }
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return true
        }

        val intent = Intent().apply {
            action = SERVICE_ACTION
            `package` = SERVICE_PACKAGE
        }

        return try {
            lastConnectionError = null
            isBinding = true
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                isBinding = false
                lastConnectionError = "bindService returned false; FireBox app may be stopped"
                Log.e(TAG, lastConnectionError.orEmpty())
                connectionListeners.forEach { it.onConnectionError(lastConnectionError) }
            }
            bound
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
            isBinding = false
            isReconnecting = false
            lastConnectionError = e.message ?: e.javaClass.simpleName
            connectionListeners.forEach { it.onConnectionError(lastConnectionError) }
            false
        }
    }

    fun disconnect() {
        if (!isConnected && !isBinding) return

        isDisconnecting = true
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind service", e)
        } finally {
            isDisconnecting = false
            capabilityService = null
            isConnected = false
            isBinding = false
            isReconnecting = false
            lastConnectionError = null
        }
    }

    fun getLastConnectionError(): String? = lastConnectionError

    fun ping(message: String = "ping"): String? =
        try {
            capabilityService?.Ping(message)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to ping service", e)
            null
        }

    fun getVersionCode(): Int = 1

    fun listModels(): List<FireBoxModelInfo>? =
        try {
            capabilityService?.ListModels()?.map { it.toClient() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list models", e)
            null
        }

    fun chatCompletion(req: FireBoxChatRequest): FireBoxChatResult {
        val service = capabilityService ?: throw IllegalStateException("FireBox service is not connected")
        val coreRequest = req.toCore()
        return try {
            service.ChatCompletion(coreRequest).toClient()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to run chat completion", e)
            throw IllegalStateException("FireBox chat completion transport failed", e)
        } finally {
            coreRequest.closeAttachmentsQuietly()
        }
    }

    fun startChatCompletionStream(
        req: FireBoxChatRequest,
        listener: ChatStreamListener,
    ): Long? {
        val service = capabilityService ?: return null
        val coreRequest = req.toCore()
        return try {
            service.StartChatCompletionStream(
                coreRequest,
                object : IChatStreamSink.Stub() {
                    override fun OnEvent(event: CoreChatStreamEvent?) {
                        event?.let { listener.onEvent(it.toClient()) }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chat stream", e)
            null
        } finally {
            coreRequest.closeAttachmentsQuietly()
        }
    }

    fun cancelChatCompletion(requestId: Long) {
        try {
            capabilityService?.CancelChatCompletion(requestId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel chat stream", e)
        }
    }

    fun createEmbeddings(req: FireBoxEmbeddingRequest): FireBoxEmbeddingResult {
        val service = capabilityService ?: throw IllegalStateException("FireBox service is not connected")
        return try {
            service.CreateEmbeddings(req.toCore()).toClient()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to create embeddings", e)
            throw IllegalStateException("FireBox embedding transport failed", e)
        }
    }

    fun <I, O> callFunction(
        modelId: String,
        spec: FireBoxFunctionSpec<I, O>,
        input: I,
    ): FireBoxFunctionResult<O> {
        val service = capabilityService ?: throw IllegalStateException("FireBox service is not connected")
        return try {
            service.CallFunction(spec.toCore(modelId, input)).toClient(spec.outputSerializer)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to call function", e)
            throw IllegalStateException("FireBox function call transport failed", e)
        }
    }

    inline fun <reified I, reified O> callFunction(
        modelId: String,
        name: String,
        description: String,
        input: I,
        temperature: Float?,
        maxOutputTokens: Int?,
    ): FireBoxFunctionResult<O> =
        callFunction(
            modelId = modelId,
            spec =
                FireBoxFunctionSpec(
                    name = name,
                    description = description,
                    inputSerializer = serializer<I>(),
                    outputSerializer = serializer<O>(),
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens,
                ),
            input = input,
        )

    fun streamChat(req: FireBoxChatRequest): Flow<FireBoxStreamEvent> =
        callbackFlow {
            val service = capabilityService
            if (service == null) {
                close(IllegalStateException("FireBox service is not connected"))
                return@callbackFlow
            }

            var requestId = -1L
            val callback =
                object : IChatStreamSink.Stub() {
                    override fun OnEvent(event: CoreChatStreamEvent?) {
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
                val coreRequest = req.toCore()
                try {
                    requestId = service.StartChatCompletionStream(coreRequest, callback)
                } finally {
                    coreRequest.closeAttachmentsQuietly()
                }
            } catch (e: Exception) {
                close(e)
                return@callbackFlow
            }

            awaitClose {
                if (requestId >= 0) {
                    runCatching { capabilityService?.CancelChatCompletion(requestId) }
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

    private fun handleConnectionLoss(
        errorMessage: String,
        attemptReconnect: Boolean,
    ) {
        if (isDisconnecting) {
            capabilityService = null
            isConnected = false
            isBinding = false
            isReconnecting = false
            return
        }
        if (!isConnected && capabilityService == null && isReconnecting) {
            Log.d(TAG, "Ignoring duplicate connection loss callback: $errorMessage")
            return
        }

        capabilityService = null
        isConnected = false
        isBinding = false
        lastConnectionError = errorMessage
        connectionListeners.forEach { it.onDisconnected() }
        connectionListeners.forEach { it.onConnectionError(lastConnectionError) }

        if (!attemptReconnect || isReconnecting) {
            return
        }
        isReconnecting = true
        connectionListeners.forEach { it.onReconnecting(lastConnectionError) }
        val rebound = connect()
        if (!rebound) {
            isReconnecting = false
        }
    }

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionError(message: String?) {}
        fun onReconnecting(message: String?) {}
    }

    fun interface ChatStreamListener {
        fun onEvent(event: FireBoxStreamEvent)
    }
}

private fun CoreChatCompletionRequest.closeAttachmentsQuietly() {
    messages.forEach { message ->
        message.attachments.forEach { attachment ->
            runCatching { attachment.data.close() }
        }
    }
}
