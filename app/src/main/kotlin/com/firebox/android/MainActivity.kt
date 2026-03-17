package com.firebox.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.firebox.android.link.PendingProviderBindRequest
import com.firebox.android.link.ProviderBindLinkParser
import com.firebox.android.ui.AllowlistScreen
import com.firebox.android.ui.ConfigurationsScreen
import com.firebox.android.ui.ConnectionsScreen
import com.firebox.android.ui.DashboardScreen
import com.firebox.android.ui.theme.AppTheme
import kotlinx.serialization.Serializable

@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    private var pendingProviderBindRequest by mutableStateOf<PendingProviderBindRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleProviderBindIntent(intent)
        }
        setContent {
            AppTheme {
                FireBoxApp(
                    providerBindRequest = pendingProviderBindRequest,
                    onProviderBindRequestConsumed = { pendingProviderBindRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProviderBindIntent(intent)
    }

    private fun handleProviderBindIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) {
            return
        }

        val dataString = intent.dataString?.trim().orEmpty()
        if (dataString.isBlank()) {
            return
        }

        ProviderBindLinkParser.parse(dataString)
            .onSuccess { payload ->
                pendingProviderBindRequest = PendingProviderBindRequest(payload = payload)
            }
            .onFailure {
                Toast.makeText(this, getString(R.string.provider_bind_invalid_link), Toast.LENGTH_SHORT).show()
            }
    }
}

@Serializable
sealed interface FireBoxNavKey : NavKey {
    @Serializable
    data object Dashboard : FireBoxNavKey

    @Serializable
    data object Connections : FireBoxNavKey

    @Serializable
    data object Configurations : FireBoxNavKey

    @Serializable
    data object Allowlist : FireBoxNavKey
}

private data class BottomNavItem(
    val key: FireBoxNavKey,
    @field:StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val bottomNavItems =
    listOf(
        BottomNavItem(
            key = FireBoxNavKey.Dashboard,
            titleRes = R.string.nav_dashboard,
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
        ),
        BottomNavItem(
            key = FireBoxNavKey.Connections,
            titleRes = R.string.nav_connections,
            selectedIcon = Icons.Filled.Share,
            unselectedIcon = Icons.Outlined.Share,
        ),
        BottomNavItem(
            key = FireBoxNavKey.Configurations,
            titleRes = R.string.nav_configurations,
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
        ),
        BottomNavItem(
            key = FireBoxNavKey.Allowlist,
            titleRes = R.string.nav_allowlist,
            selectedIcon = Icons.Filled.CheckCircle,
            unselectedIcon = Icons.Outlined.CheckCircle,
        ),
    )

@ExperimentalMaterial3Api
@Composable
fun FireBoxApp(
    providerBindRequest: PendingProviderBindRequest? = null,
    onProviderBindRequestConsumed: () -> Unit = {},
) {
    val activity = LocalActivity.current
    val backStack = rememberNavBackStack(FireBoxNavKey.Dashboard)
    val currentKey = backStack.lastOrNull()

    LaunchedEffect(providerBindRequest?.requestId) {
        if (providerBindRequest != null && backStack.lastOrNull() != FireBoxNavKey.Configurations) {
            backStack.clear()
            backStack.add(FireBoxNavKey.Configurations)
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val navContent: @Composable () -> Unit = {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                } else {
                    activity?.finish()
                }
            },
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            popTransitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            predictivePopTransitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            entryProvider =
                entryProvider {
                    entry<FireBoxNavKey.Dashboard> { DashboardScreen() }
                    entry<FireBoxNavKey.Connections> { ConnectionsScreen() }
                    entry<FireBoxNavKey.Configurations> {
                        ConfigurationsScreen(
                            providerBindRequest = providerBindRequest,
                            onProviderBindRequestConsumed = onProviderBindRequestConsumed,
                        )
                    }
                    entry<FireBoxNavKey.Allowlist> { AllowlistScreen() }
                },
        )
    }

    if (isExpanded) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(24.dp))
                bottomNavItems.forEach { navItem ->
                    val selected = currentKey == navItem.key
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            if (currentKey != navItem.key) {
                                backStack.clear()
                                backStack.add(navItem.key)
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) navItem.selectedIcon else navItem.unselectedIcon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(navItem.titleRes)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            navContent()
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    bottomNavItems.forEach { navItem ->
                        val selected = currentKey == navItem.key
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentKey != navItem.key) {
                                    backStack.clear()
                                    backStack.add(navItem.key)
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) navItem.selectedIcon else navItem.unselectedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(navItem.titleRes)) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                navContent()
            }
        }
    }
}

internal fun openFireBoxAppDetails(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    context.startActivity(intent)
}
