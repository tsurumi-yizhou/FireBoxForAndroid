package com.firebox.android.ui

import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.firebox.android.FireBoxGraph
import com.firebox.android.R
import com.firebox.android.model.ClientAccessRecord
import com.firebox.android.model.ClientConnectionInfo

private const val FireBoxClientDeclarationMetaData = "com.firebox.client.sdk.DECLARATION"

@Composable
fun AllowlistScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repo = remember(context) { FireBoxGraph.configRepository(context) }
    val connectionStateHolder = remember { FireBoxGraph.connectionStateHolder() }
    val historyRecords by repo.clientAccessRecords.collectAsState(initial = emptyList())
    val activeConnections by connectionStateHolder.connections.collectAsState(initial = emptyList())
    val packageManager = context.packageManager
    val bindPermission = remember(context) { "${context.packageName}.permission.BIND_FIREBOX_SERVICE" }

    var refreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val discoveredClients =
        remember(packageManager, bindPermission, refreshTick) {
            discoverDeclaredClients(
                packageManager = packageManager,
                bindPermission = bindPermission,
            )
        }
    val clientEntries =
        remember(discoveredClients, historyRecords, activeConnections, packageManager) {
            buildAllowlistEntries(
                packageManager = packageManager,
                discoveredClients = discoveredClients,
                historyRecords = historyRecords,
                activeConnections = activeConnections,
            )
        }

    AppScreenScaffold(title = stringResource(R.string.screen_allowlist)) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        AllowlistSummaryItem(
                            label = stringResource(R.string.allowlist_summary_declared),
                            value = discoveredClients.count { it.isDeclaredBySdk }.toString(),
                        )
                        AllowlistSummaryItem(
                            label = stringResource(R.string.allowlist_summary_granted),
                            value = discoveredClients.count { it.isPermissionGranted }.toString(),
                        )
                        AllowlistSummaryItem(
                            label = stringResource(R.string.allowlist_summary_history),
                            value = historyRecords.size.toString(),
                        )
                    }
                }

                if (clientEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.allowlist_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(clientEntries, key = { it.packageName }) { entry ->
                            AllowlistClientCard(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllowlistSummaryItem(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllowlistClientCard(entry: AllowlistClientEntry) {
    val activeText = stringResource(R.string.allowlist_status_active)
    val declaredText = stringResource(R.string.allowlist_status_declared)
    val grantedText = stringResource(R.string.allowlist_status_granted)
    val notGrantedText = stringResource(R.string.allowlist_status_not_granted)
    val historyText = stringResource(R.string.allowlist_status_history)
    val notInstalledText = stringResource(R.string.allowlist_status_not_installed)
    val statusText =
        buildList {
            if (entry.isActiveNow) add(activeText)
            if (entry.isDeclaredBySdk) add(declaredText)
            if (entry.isInstalled) {
                add(if (entry.isPermissionGranted) grantedText else notGrantedText)
            } else {
                add(notInstalledText)
            }
            if (entry.hasHistory) add(historyText)
        }.joinToString(" | ")

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.lastSeenAtMs > 0L) {
                Text(
                    text = stringResource(R.string.allowlist_last_seen, formatRelativeTime(entry.lastSeenAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.totalConnections > 0L || entry.totalRequests > 0L) {
                Text(
                    text = stringResource(
                        R.string.allowlist_connection_request_stats,
                        entry.totalConnections,
                        entry.totalRequests,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class DiscoveredClientApp(
    val packageName: String,
    val appName: String,
    val isDeclaredBySdk: Boolean,
    val isPermissionGranted: Boolean,
)

private data class AllowlistClientEntry(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean,
    val isDeclaredBySdk: Boolean,
    val isPermissionGranted: Boolean,
    val hasHistory: Boolean,
    val isActiveNow: Boolean,
    val lastSeenAtMs: Long,
    val totalConnections: Long,
    val totalRequests: Long,
)

private fun discoverDeclaredClients(
    packageManager: PackageManager,
    bindPermission: String,
): List<DiscoveredClientApp> {
    val flags =
        PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(),
        )
    return packageManager
        .getInstalledPackages(flags)
        .asSequence()
        .filter { packageInfo ->
            packageInfo.applicationInfo?.metaData?.getBoolean(FireBoxClientDeclarationMetaData, false) == true
        }
        .map { packageInfo ->
            val packageName = packageInfo.packageName
            val appName =
                packageInfo.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty().ifBlank { packageName }
            DiscoveredClientApp(
                packageName = packageName,
                appName = appName,
                isDeclaredBySdk =
                    packageInfo.applicationInfo?.metaData?.getBoolean(FireBoxClientDeclarationMetaData, false) == true,
                isPermissionGranted = packageManager.checkPermission(
                    bindPermission,
                    packageName
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }
        .sortedBy { it.appName.lowercase() }
        .toList()
}

private fun buildAllowlistEntries(
    packageManager: PackageManager,
    discoveredClients: List<DiscoveredClientApp>,
    historyRecords: List<ClientAccessRecord>,
    activeConnections: List<ClientConnectionInfo>,
): List<AllowlistClientEntry> {
    val discoveredByPackage = discoveredClients.associateBy { it.packageName }
    val historyByPackage = historyRecords.associateBy { it.packageName }
    val activeByPackage = activeConnections.filter { it.packageName.isNotBlank() }.associateBy { it.packageName }

    val packageNames = linkedSetOf<String>()
    packageNames += discoveredByPackage.keys
    packageNames += historyByPackage.keys
    packageNames += activeByPackage.keys

    return packageNames
        .asSequence()
        .map { packageName ->
            val discovered = discoveredByPackage[packageName]
            val history = historyByPackage[packageName]
            val active = activeByPackage[packageName]
            val resolvedAppName =
                discovered?.appName
                    ?: resolveAppName(packageManager = packageManager, packageName = packageName)
                    ?: packageName

            AllowlistClientEntry(
                packageName = packageName,
                appName = resolvedAppName,
                isInstalled = discovered != null,
                isDeclaredBySdk = discovered?.isDeclaredBySdk == true,
                isPermissionGranted = discovered?.isPermissionGranted == true,
                hasHistory = history != null,
                isActiveNow = active?.isActive == true,
                lastSeenAtMs = listOfNotNull(history?.lastSeenAtMs, active?.connectedAtMs).maxOrNull() ?: 0L,
                totalConnections = history?.totalConnections ?: 0L,
                totalRequests = maxOf(history?.totalRequests ?: 0L, active?.requestCount ?: 0L),
            )
        }
        .sortedWith(
            compareByDescending<AllowlistClientEntry> { it.isActiveNow }
                .thenByDescending { it.isPermissionGranted }
                .thenByDescending { it.hasHistory }
                .thenByDescending { it.lastSeenAtMs }
                .thenBy { it.appName.lowercase() },
        )
        .toList()
}

private fun resolveAppName(
    packageManager: PackageManager,
    packageName: String,
): String? =
    runCatching {
        val applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        packageManager.getApplicationLabel(applicationInfo).toString()
    }.getOrNull()

private fun formatRelativeTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
