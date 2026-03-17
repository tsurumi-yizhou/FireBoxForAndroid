package com.firebox.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firebox.android.FireBoxGraph
import com.firebox.android.R
import com.firebox.android.data.FireBoxStatsRepository
import com.firebox.android.data.db.UsageAggregate
import kotlin.math.roundToLong

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val repo = remember(context) { FireBoxGraph.statsRepository(context) }

    val today by repo.observeToday().collectAsState(initial = null)
    val yesterday by repo.observeYesterday().collectAsState(initial = null)
    val monthToDate by repo.observeMonthToDate().collectAsState(initial = UsageAggregate.Zero)

    val requestsToday = today?.requests ?: 0L
    val tokensToday = today?.tokens ?: 0L
    val priceTodayUsd = (today?.priceUsdMicros ?: 0L) / 1_000_000.0

    val requestsMonth = monthToDate.safeRequests
    val tokensMonth = monthToDate.safeTokens
    val priceMonthUsd = monthToDate.safePriceUsdMicros / 1_000_000.0
    val requestsTrend = computeTrend(requestsToday, yesterday?.requests ?: 0L)
    val tokensTrend = computeTrend(tokensToday, yesterday?.tokens ?: 0L)
    val priceTrend = computeTrend(today?.priceUsdMicros ?: 0L, yesterday?.priceUsdMicros ?: 0L)
    val placeholder = stringResource(R.string.common_placeholder_dash)

    val stats =
        listOf(
            StatCardData(
                stringResource(R.string.dashboard_requests_today),
                formatCompact(requestsToday),
                requestsTrend
            ),
            StatCardData(stringResource(R.string.dashboard_tokens_today), formatCompact(tokensToday), tokensTrend),
            StatCardData(
                stringResource(R.string.dashboard_price_today),
                stringResource(R.string.dashboard_price_value, priceTodayUsd),
                priceTrend
            ),
            StatCardData(stringResource(R.string.dashboard_requests_month), formatCompact(requestsMonth), placeholder),
            StatCardData(stringResource(R.string.dashboard_tokens_month), formatCompact(tokensMonth), placeholder),
            StatCardData(
                stringResource(R.string.dashboard_price_month),
                stringResource(R.string.dashboard_price_value, priceMonthUsd),
                placeholder
            ),
        )

    AppScreenScaffold(title = stringResource(R.string.screen_dashboard)) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val columns =
                when {
                    maxWidth < 600.dp -> 2
                    maxWidth < 840.dp -> 3
                    else -> 4
                }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(stats, key = { it.title }) { stat ->
                    StatCard(stat)
                }
            }
        }
    }
}

@Composable
fun StatCard(stat: StatCardData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stat.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedContent(
                targetState = stat.value,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "stat-value",
            ) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stat.change,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class StatCardData(
    val title: String,
    val value: String,
    val change: String
)

private fun formatCompact(value: Long): String {
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 1_000_000_000L -> "${"%.1f".format(value / 1_000_000_000.0)}B"
        abs >= 1_000_000L -> "${"%.1f".format(value / 1_000_000.0)}M"
        abs >= 1_000L -> "${"%.1f".format(value / 1_000.0)}K"
        else -> value.toString()
    }
}

private fun computeTrend(today: Long, yesterday: Long): String {
    if (yesterday == 0L) {
        return if (today == 0L) "—" else "↑ 100%"
    }
    val delta = today - yesterday
    val percent = ((kotlin.math.abs(delta).toDouble() * 100.0) / yesterday.toDouble()).roundToLong()
    return if (delta < 0) {
        "↓ ${percent}%"
    } else {
        "↑ ${percent}%"
    }
}
