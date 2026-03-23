package com.firebox.android.ui

import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firebox.android.FireBoxGraph
import com.firebox.android.R
import com.firebox.android.model.ClientConnectionInfo

@Composable
@ExperimentalMaterial3Api
fun ConnectionsScreen() {
    val context = LocalContext.current
    val connectionStateHolder = remember { FireBoxGraph.connectionStateHolder() }
    val connections by connectionStateHolder.connections.collectAsState(initial = emptyList())

    AppScreenScaffold(title = stringResource(R.string.screen_connections)) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 840.dp)
                        .padding(16.dp)
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
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ConnectionSummaryItem(stringResource(R.string.connections_total), connections.size.toString())
                        ConnectionSummaryItem(
                            stringResource(R.string.connections_active),
                            connections.count { it.isActive }.toString()
                        )
                        ConnectionSummaryItem(
                            stringResource(R.string.connections_warning),
                            connections.count { !it.isActive }.toString()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (connections.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.connections_no_active),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections, key = { it.callingUid }) { connection ->
                            ConnectionItem(connection = connection, packageManager = context.packageManager)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionSummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConnectionItem(
    connection: ClientConnectionInfo,
    packageManager: PackageManager,
) {
    val appName = remember(connection.packageName) { resolveAppName(packageManager, connection) }
    val relativeTime = remember(connection.connectedAtMs) { formatRelativeTime(connection.connectedAtMs) }
    val packageAndTimeText =
        if (connection.packageName.isBlank()) {
            relativeTime
        } else {
            stringResource(R.string.connections_package_time, connection.packageName, relativeTime)
        }
    val statusContentDescription =
        if (connection.isActive) {
            stringResource(R.string.connections_status_active)
        } else {
            stringResource(R.string.connections_status_warning)
        }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = packageAndTimeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.connections_requests_callbacks,
                        connection.requestCount,
                        connection.callbackCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (connection.hasActiveStream) {
                    Text(
                        text = stringResource(R.string.connections_streaming_now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Icon(
                imageVector = if (connection.isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = statusContentDescription,
                tint = if (connection.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun resolveAppName(
    packageManager: PackageManager,
    connection: ClientConnectionInfo,
): String {
    if (connection.packageName.isBlank()) {
        return "UID ${connection.callingUid}"
    }
    return runCatching {
        val applicationInfo = packageManager.getApplicationInfo(connection.packageName, 0)
        packageManager.getApplicationLabel(applicationInfo).toString()
    }.getOrDefault(connection.packageName)
}

private fun formatRelativeTime(connectedAtMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        connectedAtMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
