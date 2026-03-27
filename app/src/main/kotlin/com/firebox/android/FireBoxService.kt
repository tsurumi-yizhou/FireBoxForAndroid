package com.firebox.android

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.firebox.android.ai.ExecutedResponse
import com.firebox.android.ai.FireBoxAiDispatcher
import com.firebox.android.ai.FireBoxServiceException
import com.firebox.android.ai.RuntimeSnapshot
import com.firebox.android.data.ProviderModelSdkFetcher
import com.firebox.android.data.db.UsageAggregate
import com.firebox.android.model.ClientConnectionInfo
import com.firebox.android.model.ModelTarget
import com.firebox.android.model.ModelPricing
import com.firebox.android.model.ProviderConfig
import com.firebox.android.model.ProviderType
import com.firebox.android.model.RouteMediaFormat
import com.firebox.android.model.RouteModelCapabilities
import com.firebox.android.model.RouteRule
import com.firebox.android.model.RouteStrategy
import com.firebox.core.ChatCompletionRequest
import com.firebox.core.ChatCompletionResult
import com.firebox.core.ConnectionInfo
import com.firebox.core.EmbeddingRequest
import com.firebox.core.EmbeddingResult
import com.firebox.core.FunctionCallRequest
import com.firebox.core.FunctionCallResult
import com.firebox.core.ICapabilityService
import com.firebox.core.IChatStreamSink
import com.firebox.core.IControlService
import com.firebox.core.ProviderCreateRequest
import com.firebox.core.ProviderInfo
import com.firebox.core.ProviderUpdateRequest
import com.firebox.core.RouteCandidateInfo
import com.firebox.core.RouteInfo
import com.firebox.core.RouteWriteRequest
import com.firebox.core.StatsResponse
import com.firebox.core.Usage
import com.firebox.core.ClientAccessRecord as CoreClientAccessRecord
import java.time.Instant
import java.time.format.DateTimeParseException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FireBoxService : Service() {

    companion object {
        const val ACTION_BIND_CAPABILITY_SERVICE = "com.firebox.android.action.BIND_CAPABILITY_SERVICE"
        const val ACTION_BIND_CONTROL_SERVICE = "com.firebox.android.action.BIND_CONTROL_SERVICE"

        private const val TAG = "FireBoxService"
        private const val VERSION_CODE = 1
        private const val IMAGE_BIT = 1
        private const val VIDEO_BIT = 2
        private const val AUDIO_BIT = 4
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
    private val providerModelFetcher by lazy { ProviderModelSdkFetcher() }
    private val bindPermission by lazy { "${applicationContext.packageName}.permission.BIND_FIREBOX_SERVICE" }
    private val controlBindPermission by lazy { "${applicationContext.packageName}.permission.BIND_FIREBOX_CONTROL_SERVICE" }
    private val nextRequestId = AtomicLong(1L)
    private val activeStreamRequests = ConcurrentHashMap<Long, ActiveStreamRequest>()
    private val runtimeSnapshot: StateFlow<RuntimeSnapshot> by lazy {
        combine(repo.providers, repo.routes) { providers, routes ->
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

    private val capabilityBinder = object : ICapabilityService.Stub() {
        override fun Ping(message: String?): String {
            val caller = enforceCapabilityAccess()
            recordClientRequestAsync(caller.packageName, caller.callingUid)
            return "Pong: ${message.orEmpty()}"
        }

        override fun ListModels(): MutableList<com.firebox.core.ModelInfo> {
            val caller = enforceCapabilityAccess()
            recordClientRequestAsync(caller.packageName, caller.callingUid)
            connectionStateHolder.onRequestMade(caller.callingUid, caller.packageName)
            return aiDispatcher.listModels(runtimeSnapshot.value).toMutableList()
        }

        override fun ChatCompletion(req: ChatCompletionRequest?): ChatCompletionResult {
            val request = req ?: throw IllegalArgumentException("req 不能为空")
            val caller = enforceCapabilityAccess()
            return runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> ChatCompletionResult(response = response.response, error = null) },
                    failure = { error -> ChatCompletionResult(response = null, error = error) },
                ) {
                    connectionStateHolder.onRequestMade(caller.callingUid, caller.packageName)
                    recordClientRequestAsync(caller.packageName, caller.callingUid)
                    val response = aiDispatcher.chatCompletion(runtimeSnapshot.value, request)
                    recordUsageAsync(response.response.usage, response.providerType, response.providerModelId)
                    response
                }
            }
        }

        override fun StartChatCompletionStream(
            req: ChatCompletionRequest?,
            sink: IChatStreamSink?,
        ): Long {
            val request = req ?: throw IllegalArgumentException("req 不能为空")
            val callback = sink ?: throw IllegalArgumentException("sink 不能为空")
            val caller = enforceCapabilityAccess()
            val requestId = nextRequestId.getAndIncrement()
            connectionStateHolder.onRequestMade(caller.callingUid, caller.packageName)
            recordClientRequestAsync(caller.packageName, caller.callingUid)
            connectionStateHolder.onStreamStateChanged(caller.callingUid, active = true)
            val job =
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val response =
                            aiDispatcher.streamChatCompletion(runtimeSnapshot.value, requestId, request, callback)
                        if (response != null) {
                            recordUsageAsync(response.response.usage, response.providerType, response.providerModelId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        Log.e(TAG, "Stream request failed for requestId=$requestId", throwable)
                    }
                }
            activeStreamRequests[requestId] = ActiveStreamRequest(callingUid = caller.callingUid, job = job)
            job.invokeOnCompletion {
                activeStreamRequests.remove(requestId)
                connectionStateHolder.onStreamStateChanged(caller.callingUid, active = false)
            }
            return requestId
        }

        override fun CancelChatCompletion(requestId: Long) {
            val caller = enforceCapabilityAccess()
            val active = activeStreamRequests[requestId] ?: return
            if (active.callingUid != caller.callingUid) {
                throw SecurityException("无权取消其他调用方的流式请求")
            }
            active.job.cancel(CancellationException("调用方取消请求"))
        }

        override fun CreateEmbeddings(req: EmbeddingRequest?): EmbeddingResult {
            val request = req ?: throw IllegalArgumentException("req 不能为空")
            val caller = enforceCapabilityAccess()
            return runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> EmbeddingResult(response = response.response, error = null) },
                    failure = { error -> EmbeddingResult(response = null, error = error) },
                ) {
                    connectionStateHolder.onRequestMade(caller.callingUid, caller.packageName)
                    recordClientRequestAsync(caller.packageName, caller.callingUid)
                    val response = aiDispatcher.createEmbeddings(runtimeSnapshot.value, request)
                    recordUsageAsync(response.response.usage, response.providerType, response.providerModelId)
                    response
                }
            }
        }

        override fun CallFunction(modelId: String?, req: FunctionCallRequest?): FunctionCallResult {
            val selectedModelId = modelId?.trim().orEmpty()
            require(selectedModelId.isNotBlank()) { "modelId 不能为空" }
            val request = req ?: throw IllegalArgumentException("req 不能为空")
            val caller = enforceCapabilityAccess()
            return runBlocking(Dispatchers.IO) {
                fireBoxSyncResultOf(
                    success = { response -> FunctionCallResult(response = response.response, error = null) },
                    failure = { error -> FunctionCallResult(response = null, error = error) },
                ) {
                    connectionStateHolder.onRequestMade(caller.callingUid, caller.packageName)
                    recordClientRequestAsync(caller.packageName, caller.callingUid)
                    val response = aiDispatcher.callFunction(runtimeSnapshot.value, selectedModelId, request)
                    recordUsageAsync(response.response.usage, response.providerType, response.providerModelId)
                    response
                }
            }
        }
    }

    private val controlBinder = object : IControlService.Stub() {
        override fun Ping(message: String?): String {
            enforceControlPermission()
            return "Pong: ${message.orEmpty()}"
        }

        override fun Shutdown() {
            enforceControlPermission()
            stopSelf()
        }

        override fun GetVersionCode(): Int {
            enforceControlPermission()
            return VERSION_CODE
        }

        override fun GetDailyStats(year: Int, month: Int, day: Int): StatsResponse {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                statsRepo.getDailyAggregate(year, month, day).toCoreStatsResponse()
            }
        }

        override fun GetMonthlyStats(year: Int, month: Int): StatsResponse {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                statsRepo.getMonthlyAggregate(year, month).toCoreStatsResponse()
            }
        }

        override fun ListProviders(): MutableList<ProviderInfo> {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                repo.providers.first().map(::toCoreProviderInfo).toMutableList()
            }
        }

        override fun AddProvider(request: ProviderCreateRequest?): Int {
            enforceControlPermission()
            val payload = request ?: throw IllegalArgumentException("request 不能为空")
            return runBlocking(Dispatchers.IO) {
                val providerId = repo.nextProviderId()
                repo.upsertProvider(
                    ProviderConfig(
                        id = providerId,
                        type = payload.providerType.toProviderType(),
                        name = payload.name,
                        baseUrl = payload.baseUrl,
                        enabled = true,
                        apiKey = payload.apiKey,
                    ),
                )
                providerId
            }
        }

        override fun UpdateProvider(request: ProviderUpdateRequest?) {
            enforceControlPermission()
            val payload = request ?: throw IllegalArgumentException("request 不能为空")
            runBlocking(Dispatchers.IO) {
                val existing =
                    repo.providers.first().firstOrNull { it.id == payload.providerId }
                        ?: throw IllegalArgumentException("未找到 providerId=${payload.providerId}")
                repo.upsertProvider(
                    existing.copy(
                        name = payload.name,
                        baseUrl = payload.baseUrl,
                        enabledModels = payload.enabledModelIds,
                        apiKey = payload.apiKey ?: existing.apiKey,
                    ),
                )
            }
        }

        override fun DeleteProvider(providerId: Int) {
            enforceControlPermission()
            runBlocking(Dispatchers.IO) {
                repo.deleteProvider(providerId)
            }
        }

        override fun FetchProviderModels(providerId: Int): MutableList<String> {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                val provider =
                    repo.providers.first().firstOrNull { it.id == providerId }
                        ?: throw IllegalArgumentException("未找到 providerId=$providerId")
                providerModelFetcher.fetchModels(provider).getOrElse { throwable ->
                    throw IllegalStateException(throwable.message ?: "获取 provider 模型失败", throwable)
                }.toMutableList()
            }
        }

        override fun ListRoutes(): MutableList<RouteInfo> {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                repo.routes.first().map(::toCoreRouteInfo).toMutableList()
            }
        }

        override fun AddRoute(request: RouteWriteRequest?): Int {
            enforceControlPermission()
            val payload = request ?: throw IllegalArgumentException("request 不能为空")
            return runBlocking(Dispatchers.IO) {
                val nextId = (repo.routes.first().maxOfOrNull { it.id } ?: 0) + 1
                repo.upsertRouteRule(payload.toRouteRule(nextId))
                nextId
            }
        }

        override fun UpdateRoute(request: RouteWriteRequest?) {
            enforceControlPermission()
            val payload = request ?: throw IllegalArgumentException("request 不能为空")
            require(payload.id > 0) { "route id 不能为空" }
            runBlocking(Dispatchers.IO) {
                repo.upsertRouteRule(payload.toRouteRule(payload.id))
            }
        }

        override fun DeleteRoute(routeId: Int) {
            enforceControlPermission()
            runBlocking(Dispatchers.IO) {
                repo.deleteRouteRule(routeId)
            }
        }

        override fun ListConnections(): MutableList<ConnectionInfo> {
            enforceControlPermission()
            return connectionStateHolder.connections.value.map(::toCoreConnectionInfo).toMutableList()
        }

        override fun ListClientAccess(): MutableList<CoreClientAccessRecord> {
            enforceControlPermission()
            return runBlocking(Dispatchers.IO) {
                repo.clientAccessRecords.first().map(::toCoreClientAccessRecord).toMutableList()
            }
        }

        override fun UpdateClientAccessAllowed(accessId: Int, isAllowed: Boolean) {
            enforceControlPermission()
            runBlocking(Dispatchers.IO) {
                repo.updateClientAccessAllowed(accessId = accessId, isAllowed = isAllowed)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtimeSnapshot.value
    }

    override fun onBind(intent: Intent): IBinder? {
        return when (intent.action) {
            ACTION_BIND_CAPABILITY_SERVICE -> {
                enforceBindPermission()
                capabilityBinder
            }

            ACTION_BIND_CONTROL_SERVICE -> {
                enforceBindPermission()
                enforceControlPermission()
                controlBinder
            }

            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeStreamRequests.values.forEach { it.job.cancel() }
        activeStreamRequests.clear()
        serviceScope.cancel()
    }

    private fun enforceBindPermission() {
        if (Binder.getCallingUid() == Process.myUid()) {
            return
        }
        enforceCallingPermission(bindPermission, "Missing permission $bindPermission")
    }

    private fun enforceControlPermission() {
        if (Binder.getCallingUid() == Process.myUid()) {
            return
        }
        enforceCallingPermission(controlBindPermission, "Missing permission $controlBindPermission")
    }

    private fun enforceCapabilityAccess(): CallerContext {
        enforceBindPermission()
        val callingUid = Binder.getCallingUid()
        val packageName = resolvePackageName(callingUid)
        if (packageName.isBlank()) {
            throw SecurityException("无法识别调用方包名")
        }
        val denied = runBlocking(Dispatchers.IO) { isClientDenied(packageName) }
        if (denied) {
            throw SecurityException("调用方未被授权：$packageName")
        }
        return CallerContext(callingUid = callingUid, packageName = packageName)
    }

    private suspend fun isClientDenied(packageName: String): Boolean {
        val record = repo.clientAccessRecords.first().firstOrNull { it.packageName == packageName } ?: return false
        if (record.isAllowed) return false
        val deniedUntil = record.deniedUntilUtc ?: return true
        return try {
            Instant.parse(deniedUntil).isAfter(Instant.now())
        } catch (_: DateTimeParseException) {
            true
        }
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

    private fun resolvePackageName(uid: Int): String =
        packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()

    private fun toCoreProviderInfo(provider: ProviderConfig): ProviderInfo =
        ProviderInfo(
            id = provider.id,
            providerType = provider.type.displayName,
            name = provider.name,
            baseUrl = provider.baseUrl,
            enabledModelIds = provider.enabledModels,
            createdAt = provider.createdAtMs.toIsoInstant(),
            updatedAt = provider.updatedAtMs.toIsoInstant(),
        )

    private fun toCoreRouteInfo(route: RouteRule): RouteInfo =
        RouteInfo(
            id = route.id,
            routeId = route.virtualModelId,
            strategy = route.strategy.toProtocolString(),
            candidates =
                route.candidates.map { candidate ->
                    RouteCandidateInfo(
                        providerId = candidate.providerId,
                        modelId = candidate.modelId,
                    )
                },
            reasoning = route.capabilities.reasoning,
            toolCalling = route.capabilities.toolCalling,
            inputFormatsMask = route.capabilities.inputFormats.toMask(),
            outputFormatsMask = route.capabilities.outputFormats.toMask(),
            createdAt = route.createdAtMs.toIsoInstant(),
            updatedAt = route.updatedAtMs.toIsoInstant(),
        )

    private fun toCoreConnectionInfo(connection: ClientConnectionInfo): ConnectionInfo {
        val (processName, executablePath) = resolveProcessIdentity(connection.packageName)
        return ConnectionInfo(
            connectionId = connection.callingUid,
            processId = connection.callingUid,
            processName = processName,
            executablePath = executablePath,
            connectedAt = connection.connectedAtMs.toIsoInstant(),
            requestCount = connection.requestCount,
            hasActiveStream = connection.hasActiveStream,
        )
    }

    private fun toCoreClientAccessRecord(record: com.firebox.android.model.ClientAccessRecord): CoreClientAccessRecord {
        val (processName, executablePath) =
            when {
                record.processName.isNotBlank() || record.executablePath.isNotBlank() ->
                    record.processName.ifBlank { record.packageName } to
                        record.executablePath.ifBlank { record.packageName }

                else -> resolveProcessIdentity(record.packageName)
            }
        return CoreClientAccessRecord(
            id = if (record.id > 0) record.id else record.packageName.hashCode(),
            processId = record.lastCallingUid,
            processName = processName,
            executablePath = executablePath,
            requestCount = record.totalRequests,
            firstSeenAt = record.firstSeenAtMs.toIsoInstant(),
            lastSeenAt = record.lastSeenAtMs.toIsoInstant(),
            isAllowed = record.isAllowed,
            deniedUntilUtc = record.deniedUntilUtc,
        )
    }

    private fun resolveProcessIdentity(packageName: String): Pair<String, String> =
        runCatching {
            val applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            val label = packageManager.getApplicationLabel(applicationInfo).toString().ifBlank { packageName }
            label to applicationInfo.sourceDir.orEmpty().ifBlank { packageName }
        }.getOrElse {
            packageName.ifBlank { "unknown" } to packageName.ifBlank { "unknown" }
        }

    private fun UsageAggregate.toCoreStatsResponse(): StatsResponse =
        StatsResponse(
            requestCount = safeRequests,
            promptTokens = 0L,
            completionTokens = 0L,
            totalTokens = safeTokens,
            estimatedCostUsd = safePriceUsdMicros / 1_000_000.0,
        )

    private fun RouteWriteRequest.toRouteRule(id: Int): RouteRule =
        RouteRule(
            id = id,
            virtualModelId = routeId,
            strategy = strategy.toRouteStrategy(),
            candidates =
                candidates.map { candidate ->
                    ModelTarget(
                        providerId = candidate.providerId,
                        modelId = candidate.modelId,
                    )
                },
            capabilities =
                RouteModelCapabilities(
                    reasoning = reasoning,
                    toolCalling = toolCalling,
                    inputFormats = inputFormatsMask.toRouteMediaFormats(),
                    outputFormats = outputFormatsMask.toRouteMediaFormats(),
                ),
        )

    private fun String.toProviderType(): ProviderType =
        ProviderType.entries.firstOrNull {
            it.displayName.equals(this, ignoreCase = true) || it.name.equals(this, ignoreCase = true)
        } ?: throw IllegalArgumentException("不支持的 providerType: $this")

    private fun String.toRouteStrategy(): RouteStrategy =
        when {
            equals("Random", ignoreCase = true) -> RouteStrategy.Random
            equals("Ordered", ignoreCase = true) -> RouteStrategy.Failover
            equals(RouteStrategy.Failover.displayName, ignoreCase = true) -> RouteStrategy.Failover
            else -> throw IllegalArgumentException("不支持的 strategy: $this")
        }

    private fun RouteStrategy.toProtocolString(): String =
        when (this) {
            RouteStrategy.Failover -> "Ordered"
            RouteStrategy.Random -> "Random"
        }

    private fun List<RouteMediaFormat>.toMask(): Int =
        fold(0) { acc, format ->
            acc or
                when (format) {
                    RouteMediaFormat.Image -> IMAGE_BIT
                    RouteMediaFormat.Video -> VIDEO_BIT
                    RouteMediaFormat.Audio -> AUDIO_BIT
                }
        }

    private fun Int.toRouteMediaFormats(): List<RouteMediaFormat> =
        buildList {
            if (this@toRouteMediaFormats and IMAGE_BIT != 0) add(RouteMediaFormat.Image)
            if (this@toRouteMediaFormats and VIDEO_BIT != 0) add(RouteMediaFormat.Video)
            if (this@toRouteMediaFormats and AUDIO_BIT != 0) add(RouteMediaFormat.Audio)
        }

    private fun Long.toIsoInstant(): String =
        Instant.ofEpochMilli(if (this > 0L) this else 0L).toString()
}

private data class CallerContext(
    val callingUid: Int,
    val packageName: String,
)

internal suspend fun <R, T> fireBoxSyncResultOf(
    success: (R) -> T,
    failure: (String) -> T,
    block: suspend () -> R,
): T =
    try {
        success(block())
    } catch (throwable: Throwable) {
        failure(mapSyncThrowableToErrorMessage(throwable))
    }

internal fun mapSyncThrowableToErrorMessage(throwable: Throwable): String =
    when (throwable) {
        is SecurityException -> throwable.message ?: "Missing bind permission"
        is IllegalArgumentException -> throwable.message ?: "请求参数无效"
        is FireBoxServiceException -> throwable.error.message
        else -> throwable.message ?: "内部错误"
    }
