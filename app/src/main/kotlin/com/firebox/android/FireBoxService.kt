package com.firebox.android

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.firebox.android.ai.FireBoxAiDispatcher
import com.firebox.android.ai.FireBoxServiceException
import com.firebox.android.ai.RuntimeSnapshot
import com.firebox.android.model.ModelPricing
import com.firebox.android.model.ProviderType
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ChatCompletionResponse
import com.firebox.core.EmbeddingRequest
import com.firebox.core.EmbeddingResult
import com.firebox.core.EmbeddingResponse
import com.firebox.core.FireBoxError
import com.firebox.core.FunctionCallRequest
import com.firebox.core.FunctionCallResult
import com.firebox.core.FunctionCallResponse
import com.firebox.core.IChatStreamCallback
import com.firebox.core.IFireBoxService
import com.firebox.core.IServiceCallback
import com.firebox.core.ProviderSelection
import com.firebox.core.Usage
import com.firebox.core.VirtualModelInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FireBoxService : Service() {

    companion object {
        private const val TAG = "FireBoxService"
        private const val VERSION_CODE = 1
    }

    private data class ActiveStreamRequest(
        val callingUid: Int,
        val job: Job,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { FireBoxGraph.configRepository(this) }
    private val statsRepo by lazy { FireBoxGraph.statsRepository(this) }
    private val connectionStateHolder by lazy { FireBoxGraph.connectionStateHolder() }
    private val aiDispatcher by lazy { FireBoxAiDispatcher() }
    private val bindPermission by lazy { "${applicationContext.packageName}.permission.BIND_FIREBOX_SERVICE" }
    private val nextRequestId = AtomicLong(1L)
    private val activeStreamRequests = ConcurrentHashMap<Long, ActiveStreamRequest>()
    private val callbackRegistrations = TargetedCallbackRegistry<IBinder, IServiceCallback> { callback, connected ->
        try {
            callback.onConnectionStateChanged(connected)
        } catch (error: RemoteException) {
            Log.e(TAG, "Failed to notify callback registration state", error)
        }
    }
    private val runtimeSnapshot: StateFlow<RuntimeSnapshot> by lazy {
        combine(
            repo.providers,
            repo.routes,
        ) { providers, routes ->
            RuntimeSnapshot(
                providersById = providers.associateBy { it.id },
                routesByVirtualModelId = routes.associateBy { it.virtualModelId.trim() }.filterKeys { it.isNotBlank() },
            )
        }.stateIn(
            scope = serviceScope,
            started = SharingStarted.Eagerly,
            initialValue = RuntimeSnapshot(emptyMap(), emptyMap()),
        )
    }

    private val callbacks = RemoteCallbackList<IServiceCallback>()

    private val binder = object : IFireBoxService.Stub() {
        override fun performOperation(): String {
            enforceBindPermission()
            Log.d(TAG, "Performing operation")
            broadcastMessage("Operation executed")
            val snapshot = runtimeSnapshot.value
            return "Operation performed successfully from FireBox (providers=${snapshot.providersById.size}, routes=${snapshot.routesByVirtualModelId.size})"
        }

        override fun getVersionCode(): Int {
            enforceBindPermission()
            return VERSION_CODE
        }

        override fun registerCallback(callback: IBinder?) {
            enforceBindPermission()
            callback?.let {
                val callingUid = Binder.getCallingUid()
                val packageName = resolvePackageName(callingUid)
                val serviceCallback = IServiceCallback.Stub.asInterface(it)
                val registered = callbacks.register(serviceCallback)
                if (!registered) {
                    Log.w(TAG, "Failed to register callback")
                    return
                }
                callbackRegistrations.register(it, serviceCallback)
                connectionStateHolder.onClientConnected(callingUid, packageName)
                recordClientConnectedAsync(packageName, callingUid)
                Log.d(TAG, "Callback registered")
            }
        }

        override fun unregisterCallback(callback: IBinder?) {
            enforceBindPermission()
            callback?.let {
                val callingUid = Binder.getCallingUid()
                val registeredCallback = callbackRegistrations.peek(it)
                val unregistered = callbacks.unregister(registeredCallback ?: IServiceCallback.Stub.asInterface(it))
                if (!unregistered) {
                    callbackRegistrations.remove(it)
                    Log.w(TAG, "Failed to unregister callback")
                    return
                }
                callbackRegistrations.unregister(it)
                connectionStateHolder.onClientDisconnected(callingUid)
                Log.d(TAG, "Callback unregistered")
            }
        }

        override fun listVirtualModels(): MutableList<VirtualModelInfo> {
            enforceBindPermission()
            return aiDispatcher.listVirtualModels(runtimeSnapshot.value).toMutableList()
        }

        override fun chatCompletion(req: ChatCompletionRequest?): ChatCompletionResult =
            runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> ChatCompletionResult(response = response, error = null) },
                    failure = { error -> ChatCompletionResult(response = null, error = error) },
                ) {
                    enforceBindPermission()
                    val request = req ?: throw IllegalArgumentException("req 不能为空")
                    val callingUid = Binder.getCallingUid()
                    val packageName = resolvePackageName(callingUid)
                    connectionStateHolder.onRequestMade(callingUid, packageName)
                    recordClientRequestAsync(packageName, callingUid)
                    val response = aiDispatcher.chatCompletion(runtimeSnapshot.value, request)
                    recordUsageAsync(response.usage, providerTypeFrom(response.selection), response.selection.modelId)
                    response
                }
            }

        override fun startChatCompletionStream(
            req: ChatCompletionRequest?,
            cb: IChatStreamCallback?,
        ): Long {
            enforceBindPermission()
            val request = req ?: throw IllegalArgumentException("req 不能为空")
            val callback = cb ?: throw IllegalArgumentException("cb 不能为空")
            val requestId = nextRequestId.getAndIncrement()
            val callingUid = Binder.getCallingUid()
            val packageName = resolvePackageName(callingUid)
            connectionStateHolder.onRequestMade(callingUid, packageName)
            recordClientRequestAsync(packageName, callingUid)
            connectionStateHolder.onStreamStateChanged(callingUid, active = true)
            val job =
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val response =
                            aiDispatcher.streamChatCompletion(runtimeSnapshot.value, requestId, request, callback)
                        if (response != null) {
                            recordUsageAsync(
                                response.usage,
                                providerTypeFrom(response.selection),
                                response.selection.modelId
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (remote: RemoteException) {
                        Log.d(TAG, "Stream callback disconnected for requestId=$requestId", remote)
                        throw CancellationException("客户端回调已断开", remote)
                    } catch (throwable: Throwable) {
                        Log.e(TAG, "Stream request failed for requestId=$requestId", throwable)
                    }
                }
            activeStreamRequests[requestId] = ActiveStreamRequest(callingUid = callingUid, job = job)
            job.invokeOnCompletion {
                activeStreamRequests.remove(requestId)
                connectionStateHolder.onStreamStateChanged(callingUid, active = false)
            }
            return requestId
        }

        override fun cancelChatCompletion(requestId: Long) {
            enforceBindPermission()
            val active = activeStreamRequests[requestId] ?: return
            if (active.callingUid != Binder.getCallingUid()) {
                throw SecurityException("无权取消其他调用方的流式请求")
            }
            active.job.cancel(CancellationException("调用方取消请求"))
        }

        override fun createEmbeddings(req: EmbeddingRequest?): EmbeddingResult =
            runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> EmbeddingResult(response = response, error = null) },
                    failure = { error -> EmbeddingResult(response = null, error = error) },
                ) {
                    enforceBindPermission()
                    val request = req ?: throw IllegalArgumentException("req 不能为空")
                    val callingUid = Binder.getCallingUid()
                    val packageName = resolvePackageName(callingUid)
                    connectionStateHolder.onRequestMade(callingUid, packageName)
                    recordClientRequestAsync(packageName, callingUid)
                    val response = aiDispatcher.createEmbeddings(runtimeSnapshot.value, request)
                    recordUsageAsync(response.usage, providerTypeFrom(response.selection), response.selection.modelId)
                    response
                }
            }

        override fun callFunction(req: FunctionCallRequest?): FunctionCallResult =
            runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> FunctionCallResult(response = response, error = null) },
                    failure = { error -> FunctionCallResult(response = null, error = error) },
                ) {
                    enforceBindPermission()
                    val request = req ?: throw IllegalArgumentException("req 不能为空")
                    val callingUid = Binder.getCallingUid()
                    val packageName = resolvePackageName(callingUid)
                    connectionStateHolder.onRequestMade(callingUid, packageName)
                    recordClientRequestAsync(packageName, callingUid)
                    val response = aiDispatcher.callFunction(runtimeSnapshot.value, request)
                    recordUsageAsync(response.usage, providerTypeFrom(response.selection), response.selection.modelId)
                    response
                }
            }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireBoxService created")
        runtimeSnapshot.value
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "FireBoxService bound by: ${intent.`package`}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "FireBoxService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        callbacks.kill()
        callbackRegistrations.clear()
        activeStreamRequests.values.forEach { it.job.cancel() }
        activeStreamRequests.clear()
        serviceScope.cancel()
        Log.d(TAG, "FireBoxService destroyed")
    }

    private fun enforceBindPermission() {
        enforceCallingPermission(bindPermission, "Missing permission $bindPermission")
    }

    private fun recordUsageAsync(
        usage: Usage,
        providerType: ProviderType?,
        modelId: String?,
    ) {
        serviceScope.launch {
            runCatching {
                val (inputMicrosPerToken, outputMicrosPerToken) =
                    if (providerType != null && !modelId.isNullOrBlank()) {
                        ModelPricing.lookupMicrosPerToken(providerType, modelId)
                    } else {
                        0L to 0L
                    }
                statsRepo.recordUsage(
                    deltaRequests = 1,
                    deltaTokens = usage.totalTokens,
                    deltaPriceUsdMicros =
                        usage.promptTokens * inputMicrosPerToken +
                                usage.completionTokens * outputMicrosPerToken,
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to record usage", throwable)
            }
        }
    }

    private fun recordClientConnectedAsync(
        packageName: String,
        callingUid: Int,
    ) {
        serviceScope.launch {
            runCatching {
                repo.recordClientConnected(packageName = packageName, callingUid = callingUid)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to record client connection history", throwable)
            }
        }
    }

    private fun recordClientRequestAsync(
        packageName: String,
        callingUid: Int,
    ) {
        serviceScope.launch {
            runCatching {
                repo.recordClientRequest(packageName = packageName, callingUid = callingUid)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to record client request history", throwable)
            }
        }
    }

    private fun broadcastMessage(message: String) {
        val count = callbacks.beginBroadcast()
        for (index in 0 until count) {
            try {
                callbacks.getBroadcastItem(index).onMessage(message)
            } catch (error: RemoteException) {
                Log.e(TAG, "Failed to send message to callback", error)
            }
        }
        callbacks.finishBroadcast()
    }

    private fun resolvePackageName(uid: Int): String =
        packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()

    private fun providerTypeFrom(selection: ProviderSelection): ProviderType? =
        ProviderType.entries.firstOrNull {
            it.displayName == selection.providerType || it.name == selection.providerType
        }
}

internal suspend fun <R, T> fireBoxSyncResultOf(
    success: (R) -> T,
    failure: (FireBoxError) -> T,
    block: suspend () -> R,
): T =
    try {
        success(block())
    } catch (throwable: Throwable) {
        failure(mapSyncThrowableToFireBoxError(throwable))
    }

internal fun mapSyncThrowableToFireBoxError(throwable: Throwable): FireBoxError =
    when (throwable) {
        is SecurityException ->
            FireBoxError(
                code = FireBoxError.SECURITY,
                message = throwable.message ?: "Missing bind permission",
                providerType = null,
                providerModelId = null,
            )

        is IllegalArgumentException ->
            FireBoxError(
                code = FireBoxError.INVALID_ARGUMENT,
                message = throwable.message ?: "请求参数无效",
                providerType = null,
                providerModelId = null,
            )

        is FireBoxServiceException -> throwable.error

        else ->
            FireBoxError(
                code = FireBoxError.INTERNAL,
                message = throwable.message ?: "内部错误",
                providerType = null,
                providerModelId = null,
            )
    }

internal class TargetedCallbackRegistry<K, V>(
    private val notify: (V, Boolean) -> Unit,
) {
    private val lock = Any()
    private val callbacks = linkedMapOf<K, V>()

    fun register(key: K, callback: V) {
        synchronized(lock) {
            callbacks[key] = callback
        }
        notify(callback, true)
    }

    fun peek(key: K): V? =
        synchronized(lock) {
            callbacks[key]
        }

    fun unregister(key: K): V? {
        val callback =
            synchronized(lock) {
                callbacks.remove(key)
            } ?: return null
        notify(callback, false)
        return callback
    }

    fun remove(key: K): V? =
        synchronized(lock) {
            callbacks.remove(key)
        }

    fun clear() {
        synchronized(lock) {
            callbacks.clear()
        }
    }
}
