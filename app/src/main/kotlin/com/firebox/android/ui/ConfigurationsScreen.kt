package com.firebox.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebox.android.FireBoxGraph
import com.firebox.android.R
import com.firebox.android.ai.ProviderBaseUrlNormalizer
import com.firebox.android.data.ProviderModelSdkFetcher
import com.firebox.android.link.PendingProviderBindRequest
import com.firebox.android.model.*
import kotlinx.coroutines.launch

private const val HttpsSchemePrefix = "https://"
private const val SettingsKeyGeneral = "general"
private const val SettingsKeyProviders = "providers"
private const val SettingsKeyRoutes = "routes"
private const val SettingsKeyAbout = "about"
private const val SettingsKeyProviderPrefix = "provider:"
private const val SettingsKeyProviderEdit = "provider-edit"
private const val SettingsKeyRouteEdit = "route-edit"

// M3 shape tokens — resolved at call-site via MaterialTheme.shapes
// SettingsGroupShape  → extraLarge (28 dp)
// SettingsFieldShape  → large      (16 dp)
// SettingsActionShape → large      (16 dp)

private fun providerModelsSettingsKey(providerId: Int): String = "$SettingsKeyProviderPrefix$providerId"

private fun providerIdFromSettingsKey(key: String?): Int? =
    key
        ?.takeIf { it.startsWith(SettingsKeyProviderPrefix) }
        ?.removePrefix(SettingsKeyProviderPrefix)
        ?.toIntOrNull()

private fun rootSettingsKey(key: String?): String? =
    when {
        key == null -> null
        key.startsWith(SettingsKeyProviderPrefix) -> SettingsKeyProviders
        else -> key
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ConfigurationsScreen(
    providerBindRequest: PendingProviderBindRequest? = null,
    onProviderBindRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember(context) { FireBoxGraph.configRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    val providers by repo.providers.collectAsState(initial = emptyList())
    val routes by repo.routes.collectAsState(initial = emptyList())
    val quickToolModel by repo.quickToolModel.collectAsState(initial = QuickToolModelConfig())

    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingRoute by remember { mutableStateOf<RouteRule?>(null) }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(
        scaffoldDirective =
            calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(
                windowAdaptiveInfo = currentWindowAdaptiveInfo(),
            ).copy(defaultPanePreferredWidth = 400.dp),
    )
    val currentDestination = navigator.currentDestination
    val selectedDetailKey =
        currentDestination
            ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
            ?.contentKey
    val selectedRootKey = rootSettingsKey(selectedDetailKey)
    val selectedProviderId = providerIdFromSettingsKey(selectedDetailKey)
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val isNewProviderDraft = editingProvider?.let { draft -> providers.none { it.id == draft.id } } == true
    val isNewRouteDraft = editingRoute?.let { draft -> routes.none { it.id == draft.id } } == true
    val isSinglePane = navigator.scaffoldDirective.maxHorizontalPartitions <= 1
    val showDetailAsScreen = isSinglePane && selectedDetailKey != null

    BackHandler(enabled = showDetailAsScreen) {
        coroutineScope.launch {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(isSinglePane, selectedDetailKey) {
        if (!isSinglePane && selectedDetailKey == null) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyGeneral)
        }
    }

    LaunchedEffect(selectedDetailKey, selectedProvider) {
        if (selectedProviderId != null && selectedProvider == null) {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(selectedDetailKey, editingProvider, editingRoute) {
        val missingTransientDestination =
            (selectedDetailKey == SettingsKeyProviderEdit && editingProvider == null) ||
                (selectedDetailKey == SettingsKeyRouteEdit && editingRoute == null)
        if (missingTransientDestination) {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(providerBindRequest?.requestId) {
        val request = providerBindRequest ?: return@LaunchedEffect
        val nextId = repo.nextProviderId()
        editingProvider =
            ProviderConfig(
                id = nextId,
                type = request.payload.type,
                name = request.payload.name,
                baseUrl = baseUrlEditorValue(request.payload.baseUrl),
                enabled = true,
                apiKey = request.payload.apiKey,
            )
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyProviderEdit)
        onProviderBindRequestConsumed()
    }

    AppScreenScaffold(
        title =
            when {
                !showDetailAsScreen -> stringResource(R.string.screen_configurations)
                selectedDetailKey == SettingsKeyProviderEdit ->
                    stringResource(if (isNewProviderDraft) R.string.config_create_provider_title else R.string.config_edit_provider_title)
                selectedDetailKey == SettingsKeyRouteEdit ->
                    stringResource(if (isNewRouteDraft) R.string.route_create_rule_title else R.string.route_edit_rule_title)
                selectedProvider != null -> selectedProvider.name.ifBlank { stringResource(R.string.model_title_fallback) }
                selectedDetailKey == SettingsKeyGeneral -> stringResource(R.string.config_section_general)
                selectedDetailKey == SettingsKeyProviders -> stringResource(R.string.config_section_provider)
                selectedDetailKey == SettingsKeyRoutes -> stringResource(R.string.config_section_route)
                selectedDetailKey == SettingsKeyAbout -> stringResource(R.string.config_section_about)
                else -> stringResource(R.string.screen_configurations)
            },
        navigationIcon =
            if (showDetailAsScreen) {
                {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                navigator.navigateBack(BackNavigationBehavior.PopLatest)
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                }
            } else {
                null
            },
    ) { innerPadding ->
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            listPane = {
                AnimatedPane(modifier = Modifier.preferredWidth(480.dp)) {
                    ConfigurationListPane(
                        selectedRootKey = selectedRootKey,
                        providers = providers,
                        routes = routes,
                        quickToolModel = quickToolModel,
                        onOpenDestination = { destination ->
                            coroutineScope.launch {
                                if (selectedDetailKey != destination) {
                                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, destination)
                                }
                            }
                        },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    when {
                        selectedDetailKey == SettingsKeyGeneral -> {
                            GeneralSettingsPane(
                                providers = providers,
                                quickToolModel = quickToolModel,
                                onQuickToolModelChange = { updated ->
                                    coroutineScope.launch { repo.upsertQuickToolModel(updated) }
                                },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedDetailKey == SettingsKeyProviders -> {
                            ProviderSettingsPane(
                                providers = providers,
                                onAdd = {
                                    val nextId = (providers.maxOfOrNull { it.id } ?: 0) + 1
                                    editingProvider =
                                        ProviderConfig(
                                            id = nextId,
                                            type = ProviderType.OpenAI,
                                            name = "Provider #$nextId",
                                            baseUrl = "",
                                            enabled = true,
                                        )
                                    coroutineScope.launch {
                                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyProviderEdit)
                                    }
                                },
                                onEdit = { provider ->
                                    editingProvider = provider
                                    coroutineScope.launch {
                                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyProviderEdit)
                                    }
                                },
                                onDelete = { providerId -> coroutineScope.launch { repo.deleteProvider(providerId) } },
                                onManageModels = { providerId ->
                                    coroutineScope.launch {
                                        navigator.navigateTo(
                                            ListDetailPaneScaffoldRole.Detail,
                                            providerModelsSettingsKey(providerId),
                                        )
                                    }
                                },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedDetailKey == SettingsKeyRoutes -> {
                            RouteSettingsPane(
                                routes = routes,
                                onAdd = {
                                    val nextId = (routes.maxOfOrNull { it.id } ?: 0) + 1
                                    editingRoute =
                                        RouteRule(
                                            id = nextId,
                                            virtualModelId = "",
                                            strategy = RouteStrategy.Failover,
                                            candidates = emptyList(),
                                        )
                                    coroutineScope.launch {
                                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyRouteEdit)
                                    }
                                },
                                onEdit = { rule ->
                                    editingRoute = rule
                                    coroutineScope.launch {
                                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, SettingsKeyRouteEdit)
                                    }
                                },
                                onDelete = { ruleId -> coroutineScope.launch { repo.deleteRouteRule(ruleId) } },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedDetailKey == SettingsKeyAbout -> {
                            AboutSettingsPane(
                                providerCount = providers.size,
                                routeCount = routes.size,
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedProvider != null -> {
                            ProviderModelsPane(
                                provider = selectedProvider,
                                onProviderChange = { updated -> coroutineScope.launch { repo.upsertProvider(updated) } },
                                onBack = {
                                    coroutineScope.launch {
                                        navigator.navigateBack(BackNavigationBehavior.PopLatest)
                                    }
                                },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedDetailKey == SettingsKeyProviderEdit && editingProvider != null -> {
                            ProviderEditorPane(
                                provider = editingProvider!!,
                                isNew = isNewProviderDraft,
                                onDismiss = {
                                    editingProvider = null
                                    coroutineScope.launch {
                                        navigator.navigateBack(BackNavigationBehavior.PopLatest)
                                    }
                                },
                                onSave = { updated ->
                                    coroutineScope.launch { repo.upsertProvider(updated) }
                                    editingProvider = null
                                    coroutineScope.launch {
                                        navigator.navigateBack(BackNavigationBehavior.PopLatest)
                                    }
                                },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        selectedDetailKey == SettingsKeyRouteEdit && editingRoute != null -> {
                            RouteEditorPane(
                                rule = editingRoute!!,
                                providers = providers,
                                isNew = isNewRouteDraft,
                                onDismiss = {
                                    editingRoute = null
                                    coroutineScope.launch {
                                        navigator.navigateBack(BackNavigationBehavior.PopLatest)
                                    }
                                },
                                onSave = { updated ->
                                    coroutineScope.launch { repo.upsertRouteRule(updated) }
                                    editingRoute = null
                                    coroutineScope.launch {
                                        navigator.navigateBack(BackNavigationBehavior.PopLatest)
                                    }
                                },
                                showHeader = !showDetailAsScreen,
                            )
                        }

                        else -> {
                        SettingsDetailPlaceholder(
                            providerCount = providers.size,
                            routeCount = routes.size,
                        )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsPaneHeader(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable (() -> Unit))? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (navigationIcon != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    navigationIcon()
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsGroupSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsPageSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsPaneContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

@Composable
private fun SettingsFormActions(
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onDismiss,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(
            onClick = onSave,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}

@Composable
private fun SettingsLazyPage(
    maxWidth: Dp = 960.dp,
    headerTitle: String? = null,
    headerSummary: String? = null,
    content: LazyListScope.() -> Unit,
) {
    SettingsPageSurface {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = maxWidth),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (headerTitle != null && headerSummary != null) {
                    item {
                        SettingsPaneHeader(
                            title = headerTitle,
                            summary = headerSummary,
                        )
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun SettingsIndexRow(
    title: String,
    summary: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    Surface(
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SettingsRowLayout(
            title = title,
            summary = summary,
            leading = { SettingsIconBadge(icon = icon, emphasized = selected) },
            trailing = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ProviderListRow(
    provider: ProviderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManageModels: () -> Unit,
) {
    SettingsRowLayout(
        title = provider.name.ifBlank { stringResource(R.string.config_unnamed_provider) },
        summary =
            "${provider.type.displayName} · ${
                stringResource(
                    R.string.config_provider_enabled_models_count,
                    provider.enabledModels.size,
                )
            }\n${provider.baseUrl.ifBlank { stringResource(R.string.common_placeholder_dash) }}",
        leading = { SettingsIconBadge(icon = Icons.Default.Cloud) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.config_delete_provider_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onManageModels),
    )
}

@Composable
private fun RouteListRow(
    rule: RouteRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val capabilitySummary =
        routeCapabilitySummary(
            capabilities = rule.capabilities,
            reasoningLabel = stringResource(R.string.route_capability_reasoning),
            toolCallingLabel = stringResource(R.string.route_capability_tool_calling),
            inputLabel = stringResource(R.string.route_multimodal_input_label),
            outputLabel = stringResource(R.string.route_multimodal_output_label),
            imageLabel = stringResource(R.string.route_media_format_image),
            videoLabel = stringResource(R.string.route_media_format_video),
            audioLabel = stringResource(R.string.route_media_format_audio),
        )
    val summary =
        buildString {
            append("${rule.strategy.displayName} · ${stringResource(R.string.route_candidates_count, rule.candidates.size)}")
            if (capabilitySummary.isNotBlank()) {
                append('\n')
                append(capabilitySummary)
            }
        }
    SettingsRowLayout(
        title = rule.virtualModelId.ifBlank { stringResource(R.string.route_virtual_model_rule) },
        summary = summary,
        leading = { SettingsIconBadge(icon = Icons.Default.Route) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.route_delete_rule_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
    )
}

@Composable
private fun SettingsEmptyRow(text: String) {
    SettingsRowLayout(
        title = text,
        summary = "",
        leading = { SettingsIconBadge(icon = Icons.Default.Info) },
    )
}

@Composable
private fun AboutInfoRow(
    title: String,
    summary: String,
    icon: ImageVector,
) {
    SettingsRowLayout(
        title = title,
        summary = summary,
        leading = { SettingsIconBadge(icon = icon) },
    )
}

@Composable
private fun QuickToolModelSummaryRow(
    title: String,
    summary: String,
) {
    SettingsRowLayout(
        title = title,
        summary = summary,
        leading = { SettingsIconBadge(icon = Icons.Default.Bolt, emphasized = true) },
    )
}

@Composable
private fun SettingsIconBadge(
    icon: ImageVector,
    emphasized: Boolean = false,
) {
    val containerColor =
        if (emphasized) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
        }
    val iconTint =
        if (emphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
        }
    }
}

@Composable
private fun SettingsRowLayout(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (summary.isBlank()) 0.dp else 4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun ProviderEditorPane(
    provider: ProviderConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    showHeader: Boolean,
) {
    var draft by remember(provider) {
        mutableStateOf(
            provider.copy(
                baseUrl = baseUrlEditorValue(provider.baseUrl),
                apiKey = if (isNew) provider.apiKey else "",
            ),
        )
    }

    SettingsPageSurface {
        SettingsPaneContent {
            if (showHeader) {
                SettingsPaneHeader(
                    title = stringResource(if (isNew) R.string.config_create_provider_title else R.string.config_edit_provider_title),
                    summary = stringResource(R.string.config_section_provider_summary),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                )
            }

            SettingsGroupSurface {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text(stringResource(R.string.config_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    )

                    ProviderTypeDropdown(
                        value = draft.type,
                        onValueChange = { draft = draft.copy(type = it) },
                    )

                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = sanitizeBaseUrlEditorInput(it)) },
                        label = { Text(stringResource(R.string.config_base_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    )

                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it) },
                        label = { Text(stringResource(R.string.config_api_key_label)) },
                        placeholder = {
                            if (!isNew) {
                                Text(stringResource(R.string.config_api_key_edit_placeholder))
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }

            SettingsFormActions(
                onDismiss = onDismiss,
                onSave = {
                    onSave(
                        draft.copy(
                            baseUrl = normalizeBaseUrlDraft(draft.type, draft.baseUrl),
                            apiKey = draft.apiKey,
                        ),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdownField(
    valueText: String,
    label: String,
    items: List<T>,
    onSelect: (T) -> Unit,
    itemText: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    shape: Shape = MaterialTheme.shapes.large,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder =
                placeholder?.let { placeholderText ->
                    { Text(placeholderText) }
                },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = shape,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemText(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeDropdown(
    value: ProviderType,
    onValueChange: (ProviderType) -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    modifier: Modifier = Modifier,
) {
    SettingsDropdownField(
        valueText = value.displayName,
        label = stringResource(R.string.config_provider_type_label),
        items = ProviderType.entries,
        onSelect = onValueChange,
        itemText = { it.displayName },
        modifier = modifier,
        shape = shape,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteEditorPane(
    rule: RouteRule,
    providers: List<ProviderConfig>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
    showHeader: Boolean,
) {
    var draft by remember(rule) { mutableStateOf(rule) }
    val availableProviders = availableProvidersForRoute(draft, providers)

    SettingsPageSurface {
        SettingsPaneContent {
            if (showHeader) {
                SettingsPaneHeader(
                    title = stringResource(if (isNew) R.string.route_create_rule_title else R.string.route_edit_rule_title),
                    summary = stringResource(R.string.config_section_route_summary),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                )
            }

            SettingsGroupSurface {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = draft.virtualModelId,
                        onValueChange = { draft = draft.copy(virtualModelId = it) },
                        label = { Text(stringResource(R.string.route_match_model_id_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    )

                    RouteCapabilitiesSection(
                        capabilities = draft.capabilities,
                        onChange = { draft = draft.copy(capabilities = it) },
                    )

                    RouteStrategyDropdown(
                        value = draft.strategy,
                        onValueChange = { draft = draft.copy(strategy = it) },
                    )
                }
            }

            SettingsGroupSurface {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.route_candidates_count, draft.candidates.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FilledTonalButton(
                            onClick = {
                                val firstProvider = availableProviders.firstOrNull()
                                draft = draft.copy(
                                    candidates =
                                        draft.candidates + listOf(
                                            ModelTarget(
                                                providerId = firstProvider?.id ?: 0,
                                                modelId = firstProvider?.enabledModels?.firstOrNull().orEmpty(),
                                            ),
                                        ),
                                )
                            },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.route_add_target))
                        }
                    }

                    if (draft.candidates.isEmpty()) {
                        SettingsEmptyRow(text = stringResource(R.string.route_no_target_models))
                    } else {
                        draft.candidates.forEachIndexed { candidateIndex, target ->
                            val fallbackProvider = availableProviders.firstOrNull()
                            val resolvedProviderId =
                                when {
                                    target.providerId != 0 && availableProviders.any { it.id == target.providerId } -> target.providerId
                                    target.provider != null -> availableProviders.firstOrNull { it.type == target.provider }?.id
                                        ?: fallbackProvider?.id ?: 0
                                    else -> fallbackProvider?.id ?: 0
                                }
                            val selectedProvider = providers.firstOrNull { it.id == resolvedProviderId }
                            val enabledModelIds = selectedProvider?.enabledModels.orEmpty()
                            val requestedModelId = target.modelId.ifBlank { target.model.orEmpty() }
                            val resolvedModelId =
                                when {
                                    requestedModelId in enabledModelIds -> requestedModelId
                                    enabledModelIds.isNotEmpty() -> enabledModelIds.first()
                                    else -> requestedModelId
                                }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text(stringResource(R.string.route_target_priority, candidateIndex + 1)) },
                                        )
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    if (candidateIndex <= 0) return@IconButton
                                                    val updated = draft.candidates.toMutableList()
                                                    val temp = updated[candidateIndex - 1]
                                                    updated[candidateIndex - 1] = updated[candidateIndex]
                                                    updated[candidateIndex] = temp
                                                    draft = draft.copy(candidates = updated)
                                                },
                                                enabled = candidateIndex > 0,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.KeyboardArrowUp,
                                                    contentDescription = stringResource(R.string.route_move_up_cd),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (candidateIndex >= draft.candidates.lastIndex) return@IconButton
                                                    val updated = draft.candidates.toMutableList()
                                                    val temp = updated[candidateIndex + 1]
                                                    updated[candidateIndex + 1] = updated[candidateIndex]
                                                    updated[candidateIndex] = temp
                                                    draft = draft.copy(candidates = updated)
                                                },
                                                enabled = candidateIndex < draft.candidates.lastIndex,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.KeyboardArrowDown,
                                                    contentDescription = stringResource(R.string.route_move_down_cd),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    draft = draft.copy(
                                                        candidates = draft.candidates.toMutableList().also { it.removeAt(candidateIndex) },
                                                    )
                                                },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = stringResource(R.string.route_delete_target_cd),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }

                                    ProviderInstanceDropdown(
                                        providers = availableProviders,
                                        selectedProviderId = resolvedProviderId,
                                        onSelect = { newProviderId ->
                                            val newProvider = providers.firstOrNull { it.id == newProviderId }
                                            val providerEnabledModels = newProvider?.enabledModels.orEmpty()
                                            val newModelId =
                                                if (resolvedModelId in providerEnabledModels) {
                                                    resolvedModelId
                                                } else {
                                                    providerEnabledModels.firstOrNull().orEmpty()
                                                }
                                            val updatedTarget =
                                                target.copy(
                                                    providerId = newProviderId,
                                                    modelId = newModelId,
                                                    provider = null,
                                                    model = null,
                                                )
                                            draft = draft.copy(
                                                candidates = draft.candidates.toMutableList().also { it[candidateIndex] = updatedTarget },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    ModelIdDropdown(
                                        modelIds = enabledModelIds,
                                        selectedModelId = resolvedModelId,
                                        onSelect = { newModelId ->
                                            val updatedTarget =
                                                target.copy(
                                                    providerId = resolvedProviderId,
                                                    modelId = newModelId,
                                                    provider = null,
                                                    model = null,
                                                )
                                            draft = draft.copy(
                                                candidates = draft.candidates.toMutableList().also { it[candidateIndex] = updatedTarget },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsFormActions(
                onDismiss = onDismiss,
                onSave = { onSave(draft) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteStrategyDropdown(
    value: RouteStrategy,
    onValueChange: (RouteStrategy) -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    modifier: Modifier = Modifier,
) {
    SettingsDropdownField(
        valueText = value.displayName,
        label = stringResource(R.string.route_strategy_label),
        items = RouteStrategy.entries,
        onSelect = onValueChange,
        itemText = { it.displayName },
        modifier = modifier,
        shape = shape,
    )
}

@Composable
private fun RouteCapabilitiesSection(
    capabilities: RouteModelCapabilities,
    onChange: (RouteModelCapabilities) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.route_capabilities_label),
            style = MaterialTheme.typography.labelLarge,
        )

        CapabilityToggleRow(
            label = stringResource(R.string.route_capability_reasoning),
            checked = capabilities.reasoning,
            onCheckedChange = { onChange(capabilities.copy(reasoning = it)) },
        )
        CapabilityToggleRow(
            label = stringResource(R.string.route_capability_tool_calling),
            checked = capabilities.toolCalling,
            onCheckedChange = { onChange(capabilities.copy(toolCalling = it)) },
        )

        Text(
            text = stringResource(R.string.route_multimodal_input_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MediaFormatSelector(
            selectedFormats = capabilities.inputFormats,
            onToggle = { format ->
                onChange(capabilities.copy(inputFormats = capabilities.inputFormats.toggle(format)))
            },
        )

        Text(
            text = stringResource(R.string.route_multimodal_output_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MediaFormatSelector(
            selectedFormats = capabilities.outputFormats,
            onToggle = { format ->
                onChange(capabilities.copy(outputFormats = capabilities.outputFormats.toggle(format)))
            },
        )
    }
}

@Composable
private fun CapabilityToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun MediaFormatSelector(
    selectedFormats: List<RouteMediaFormat>,
    onToggle: (RouteMediaFormat) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RouteMediaFormat.entries.forEach { format ->
            FilterChip(
                selected = format in selectedFormats,
                onClick = { onToggle(format) },
                label = { Text(mediaFormatLabel(format)) },
            )
        }
    }
}

private fun availableProvidersForRoute(
    rule: RouteRule,
    providers: List<ProviderConfig>,
): List<ProviderConfig> {
    val providersWithEnabledModels = providers.filter { it.enabledModels.isNotEmpty() }
    return if (isEmbeddingRoute(rule)) {
        providersWithEnabledModels.filterNot { it.type == ProviderType.Anthropic }
    } else {
        providersWithEnabledModels
    }
}

private fun isEmbeddingRoute(rule: RouteRule): Boolean =
    rule.virtualModelId.contains("embedding", ignoreCase = true) ||
            rule.candidates.any {
                it.modelId.contains("embedding", ignoreCase = true) ||
                        it.model.orEmpty().contains("embedding", ignoreCase = true)
            }

@Composable
private fun mediaFormatLabel(format: RouteMediaFormat): String =
    when (format) {
        RouteMediaFormat.Image -> stringResource(R.string.route_media_format_image)
        RouteMediaFormat.Video -> stringResource(R.string.route_media_format_video)
        RouteMediaFormat.Audio -> stringResource(R.string.route_media_format_audio)
    }

private fun routeCapabilitySummary(
    capabilities: RouteModelCapabilities,
    reasoningLabel: String,
    toolCallingLabel: String,
    inputLabel: String,
    outputLabel: String,
    imageLabel: String,
    videoLabel: String,
    audioLabel: String,
): String {
    val parts = mutableListOf<String>()
    if (capabilities.reasoning) {
        parts += reasoningLabel
    }
    if (capabilities.toolCalling) {
        parts += toolCallingLabel
    }
    val inputFormats =
        capabilities.inputFormats
            .map { it.summaryLabel(imageLabel = imageLabel, videoLabel = videoLabel, audioLabel = audioLabel) }
    if (inputFormats.isNotEmpty()) {
        parts += "$inputLabel: ${inputFormats.joinToString()}"
    }
    val outputFormats =
        capabilities.outputFormats
            .map { it.summaryLabel(imageLabel = imageLabel, videoLabel = videoLabel, audioLabel = audioLabel) }
    if (outputFormats.isNotEmpty()) {
        parts += "$outputLabel: ${outputFormats.joinToString()}"
    }
    return parts.joinToString(" · ")
}

private fun RouteMediaFormat.summaryLabel(
    imageLabel: String,
    videoLabel: String,
    audioLabel: String,
): String =
    when (this) {
        RouteMediaFormat.Image -> imageLabel
        RouteMediaFormat.Video -> videoLabel
        RouteMediaFormat.Audio -> audioLabel
    }

private fun List<RouteMediaFormat>.toggle(format: RouteMediaFormat): List<RouteMediaFormat> =
    if (format in this) {
        filterNot { it == format }
    } else {
        this + format
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsPane(
    provider: ProviderConfig?,
    onProviderChange: (ProviderConfig) -> Unit,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (provider == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.model_select_provider))
        }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val fetcher = remember { ProviderModelSdkFetcher() }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable(provider.id) { mutableStateOf("") }
    var isLoading by remember(provider.id) { mutableStateOf(false) }
    var hasError by remember(provider.id) { mutableStateOf(false) }
    var fetchedModels by remember(provider.id) { mutableStateOf<List<String>>(emptyList()) }
    val http404HintText = stringResource(R.string.model_http_404_hint)
    val authHintText = stringResource(R.string.model_auth_hint)
    val networkHintText = stringResource(R.string.model_network_hint)
    val genericHintText = stringResource(R.string.model_generic_hint)
    val retryText = stringResource(R.string.common_retry)

    fun refresh() {
        coroutineScope.launch {
            isLoading = true
            hasError = false
            fetcher
                .fetchModels(provider)
                .onSuccess { fetchedModels = it }
                .onFailure { throwable ->
                    hasError = true
                    val message =
                        throwable.toModelFetchMessage(
                            http404HintText = http404HintText,
                            authHintText = authHintText,
                            networkHintText = networkHintText,
                            genericHintText = genericHintText,
                        )
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result =
                        snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = retryText,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        refresh()
                    }
                }
            isLoading = false
        }
    }

    LaunchedEffect(provider.id, provider.type, provider.baseUrl, provider.apiKey) {
        focusManager.clearFocus(force = true)
        snackbarHostState.currentSnackbarData?.dismiss()
        refresh()
    }

    val enabledSet = remember(provider.enabledModels) { provider.enabledModels.toSet() }
    val filteredModels =
        remember(fetchedModels, query) {
            if (query.isBlank()) {
                fetchedModels
            } else {
                val q = query.trim()
                fetchedModels.filter { it.contains(q, ignoreCase = true) }
            }
        }
    val groupedModels = remember(filteredModels) { detectModelGroups(filteredModels) }
    var collapsedGroupNames by rememberSaveable(provider.id) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(groupedModels) {
        val validGroupNames = groupedModels.mapNotNull { it.name }.toSet()
        // 初始状态下所有分组都收起
        collapsedGroupNames = if (collapsedGroupNames.isEmpty()) {
            validGroupNames
        } else {
            collapsedGroupNames.intersect(validGroupNames)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 960.dp)
                    .padding(
                        horizontal = if (showHeader) 16.dp else 12.dp,
                        vertical = if (showHeader) 16.dp else 12.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    }
                    Text(
                        text = provider.name.ifBlank { stringResource(R.string.model_title_fallback) },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )

                }
            }

            DockedSearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { },
                        expanded = false,
                        onExpandedChange = { },
                        placeholder = { Text(stringResource(R.string.model_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.common_clear)
                                    )
                                }
                            }
                        } else null,
                    )
                },
                expanded = false,
                onExpandedChange = { },
                modifier = Modifier.fillMaxWidth(),
            ) { }

            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            AnimatedVisibility(
                visible = !isLoading && !hasError && fetchedModels.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(text = stringResource(R.string.model_empty))
            }

            if (filteredModels.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groupedModels.forEach { group ->
                        val groupName = group.name
                        val isCollapsed = groupName != null && groupName in collapsedGroupNames
                        if (groupName != null) {
                            item(key = "model-group-$groupName") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .clickable {
                                            collapsedGroupNames =
                                                if (groupName in collapsedGroupNames) {
                                                    collapsedGroupNames - groupName
                                                } else {
                                                    collapsedGroupNames + groupName
                                                }
                                        },
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = groupName,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                            contentDescription = null,
                                        )
                                    }
                                }
                            }
                        }

                        if (!isCollapsed) {
                            items(group.modelIds, key = { it }) { modelId ->
                                val enabled = modelId in enabledSet
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem(),
                                    colors = CardDefaults.outlinedCardColors(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(text = modelId, modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = { checked ->
                                                val next =
                                                    if (checked) {
                                                        (provider.enabledModels + modelId).distinct().sorted()
                                                    } else {
                                                        provider.enabledModels.filterNot { it == modelId }
                                                    }
                                                onProviderChange(
                                                    provider.copy(
                                                        enabledModels = next,
                                                        models = emptyList()
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { refresh() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
        )
    }
}

@Composable
private fun ConfigurationListPane(
    selectedRootKey: String?,
    providers: List<ProviderConfig>,
    routes: List<RouteRule>,
    quickToolModel: QuickToolModelConfig,
    onOpenDestination: (String) -> Unit,
) {
    val quickToolSummary =
        remember(providers, quickToolModel) {
            val provider = providers.firstOrNull { it.id == quickToolModel.providerId }
            when {
                provider != null && quickToolModel.modelId.isNotBlank() -> "${provider.name} · ${quickToolModel.modelId}"
                else -> ""
            }
        }
    val context = LocalContext.current
    val versionName =
        remember(context.packageName) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }

    SettingsLazyPage(maxWidth = 560.dp) {
        item {
            SettingsGroupSurface {
                SettingsIndexRow(
                    title = stringResource(R.string.config_section_general),
                    summary = quickToolSummary.ifBlank { stringResource(R.string.config_section_general_summary) },
                    icon = Icons.Default.Tune,
                    selected = selectedRootKey == SettingsKeyGeneral,
                    onClick = { onOpenDestination(SettingsKeyGeneral) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsIndexRow(
                    title = stringResource(R.string.config_section_provider),
                    summary = stringResource(R.string.config_about_provider_count_value, providers.size),
                    icon = Icons.Default.Cloud,
                    selected = selectedRootKey == SettingsKeyProviders,
                    onClick = { onOpenDestination(SettingsKeyProviders) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsIndexRow(
                    title = stringResource(R.string.config_section_route),
                    summary = stringResource(R.string.config_about_route_count_value, routes.size),
                    icon = Icons.Default.Route,
                    selected = selectedRootKey == SettingsKeyRoutes,
                    onClick = { onOpenDestination(SettingsKeyRoutes) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsIndexRow(
                    title = stringResource(R.string.config_section_about),
                    summary = versionName.ifBlank { stringResource(R.string.config_section_about_summary) },
                    icon = Icons.Default.Info,
                    selected = selectedRootKey == SettingsKeyAbout,
                    onClick = { onOpenDestination(SettingsKeyAbout) },
                )
            }
        }
    }
}

@Composable
private fun SettingsDetailPlaceholder(
    providerCount: Int,
    routeCount: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_detail_placeholder_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.config_detail_placeholder_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.config_about_provider_count_value, providerCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.config_about_route_count_value, routeCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun GeneralSettingsPane(
    providers: List<ProviderConfig>,
    quickToolModel: QuickToolModelConfig,
    onQuickToolModelChange: (QuickToolModelConfig) -> Unit,
    showHeader: Boolean,
) {
    SettingsLazyPage(
        headerTitle = if (showHeader) stringResource(R.string.config_section_general) else null,
        headerSummary = if (showHeader) stringResource(R.string.config_section_general_summary) else null,
    ) {
        item {
            QuickToolModelCard(
                providers = providers,
                config = quickToolModel,
                onChange = onQuickToolModelChange,
            )
        }
    }
}

@Composable
private fun AboutSettingsPane(
    providerCount: Int,
    routeCount: Int,
    showHeader: Boolean,
) {
    val context = LocalContext.current
    val packageName = context.packageName
    val versionName =
        remember(packageName) {
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
        }
    SettingsLazyPage(
        headerTitle = if (showHeader) stringResource(R.string.config_section_about) else null,
        headerSummary = if (showHeader) stringResource(R.string.config_section_about_summary) else null,
    ) {
        item {
            SettingsGroupSurface {
                AboutInfoRow(
                    title = stringResource(R.string.config_about_version),
                    summary = versionName.ifBlank { stringResource(R.string.common_placeholder_dash) },
                    icon = Icons.Default.Info,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutInfoRow(
                    title = stringResource(R.string.config_about_package),
                    summary = packageName,
                    icon = Icons.Default.Android,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutInfoRow(
                    title = stringResource(R.string.config_about_provider_count),
                    summary = stringResource(R.string.config_about_provider_count_value, providerCount),
                    icon = Icons.Default.Cloud,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutInfoRow(
                    title = stringResource(R.string.config_about_route_count),
                    summary = stringResource(R.string.config_about_route_count_value, routeCount),
                    icon = Icons.Default.Route,
                )
            }
        }
    }
}

@Composable
private fun ProviderSettingsPane(
    providers: List<ProviderConfig>,
    onAdd: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (Int) -> Unit,
    onManageModels: (Int) -> Unit,
    showHeader: Boolean,
) {
    SettingsLazyPage(
        headerTitle = if (showHeader) stringResource(R.string.config_section_provider) else null,
        headerSummary = if (showHeader) stringResource(R.string.config_section_provider_summary) else null,
    ) {
        item {
            FilledTonalButton(
                onClick = onAdd,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.config_add_provider))
            }
        }
        item {
            SettingsGroupSurface {
                if (providers.isEmpty()) {
                    SettingsEmptyRow(text = stringResource(R.string.config_empty_provider))
                } else {
                    providers.forEachIndexed { index, provider ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        ProviderListRow(
                            provider = provider,
                            onEdit = { onEdit(provider) },
                            onDelete = { onDelete(provider.id) },
                            onManageModels = { onManageModels(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteSettingsPane(
    routes: List<RouteRule>,
    onAdd: () -> Unit,
    onEdit: (RouteRule) -> Unit,
    onDelete: (Int) -> Unit,
    showHeader: Boolean,
) {
    SettingsLazyPage(
        headerTitle = if (showHeader) stringResource(R.string.config_section_route) else null,
        headerSummary = if (showHeader) stringResource(R.string.config_section_route_summary) else null,
    ) {
        item {
            FilledTonalButton(
                onClick = onAdd,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.route_add_rule))
            }
        }
        item {
            SettingsGroupSurface {
                if (routes.isEmpty()) {
                    SettingsEmptyRow(text = stringResource(R.string.route_empty_rule))
                } else {
                    routes.forEachIndexed { index, rule ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        RouteListRow(
                            rule = rule,
                            onEdit = { onEdit(rule) },
                            onDelete = { onDelete(rule.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickToolModelCard(
    providers: List<ProviderConfig>,
    config: QuickToolModelConfig,
    onChange: (QuickToolModelConfig) -> Unit,
) {
    val availableProviders = providers.filter { it.enabledModels.isNotEmpty() }
    val resolvedProvider =
        availableProviders.firstOrNull { it.id == config.providerId } ?: availableProviders.firstOrNull()
    val resolvedProviderId = resolvedProvider?.id ?: 0
    val availableModelIds = resolvedProvider?.enabledModels.orEmpty()
    val resolvedModelId =
        when {
            config.modelId in availableModelIds -> config.modelId
            availableModelIds.isNotEmpty() -> availableModelIds.first()
            else -> ""
        }

    SettingsGroupSurface {
        QuickToolModelSummaryRow(
            title = stringResource(R.string.config_quick_tool_model),
            summary =
                if (resolvedProvider != null && resolvedModelId.isNotBlank()) {
                    "${resolvedProvider.name} · $resolvedModelId"
                } else {
                    stringResource(R.string.config_quick_tool_model_unset)
                },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.config_quick_tool_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (availableProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.model_dropdown_empty_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ProviderInstanceDropdown(
                    providers = availableProviders,
                    selectedProviderId = resolvedProviderId,
                    onSelect = { providerId ->
                        val modelId = availableProviders.firstOrNull { it.id == providerId }?.enabledModels?.firstOrNull().orEmpty()
                        onChange(
                            QuickToolModelConfig(
                                providerId = providerId,
                                modelId = modelId,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelIdDropdown(
                    modelIds = availableModelIds,
                    selectedModelId = resolvedModelId,
                    onSelect = { modelId ->
                        onChange(
                            QuickToolModelConfig(
                                providerId = resolvedProviderId,
                                modelId = modelId,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private data class ModelGroup(
    val name: String?,
    val modelIds: List<String>,
)

private fun sanitizeBaseUrlEditorInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) {
        return HttpsSchemePrefix
    }

    val collapsed =
        Regex("^(?:(https?)://)+", RegexOption.IGNORE_CASE)
            .replace(trimmed) { matchResult ->
                "${matchResult.groupValues[1].lowercase()}://"
            }

    return when {
        collapsed.startsWith("https://", ignoreCase = true) -> collapsed
        collapsed.startsWith("http://", ignoreCase = true) -> collapsed
        else -> HttpsSchemePrefix + collapsed.removePrefix("//")
    }
}

private fun baseUrlEditorValue(baseUrl: String): String =
    if (baseUrl.isBlank()) {
        HttpsSchemePrefix
    } else {
        sanitizeBaseUrlEditorInput(baseUrl)
    }

private fun normalizeBaseUrlDraft(type: ProviderType, baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isBlank() || trimmed.equals(HttpsSchemePrefix, ignoreCase = true) || trimmed.equals(
            "http://",
            ignoreCase = true
        )
    ) {
        return ""
    }

    return runCatching {
        ProviderBaseUrlNormalizer.normalizeProviderBaseUrl(type, trimmed)
    }.getOrElse { trimmed }
}

private fun detectModelGroups(modelIds: List<String>): List<ModelGroup> {
    if (modelIds.isEmpty()) {
        return emptyList()
    }

    val ungrouped = mutableListOf<String>()
    val grouped = linkedMapOf<String, MutableList<String>>()

    modelIds.forEach { modelId ->
        val slashIndex = modelId.indexOf('/')
        val hasGroup = slashIndex > 0
        if (!hasGroup) {
            ungrouped += modelId
            return@forEach
        }

        val groupName = modelId.substring(0, slashIndex)
        grouped.getOrPut(groupName) { mutableListOf() }.add(modelId)
    }

    return buildList {
        if (ungrouped.isNotEmpty()) {
            add(ModelGroup(name = null, modelIds = ungrouped))
        }
        grouped.forEach { (groupName, ids) ->
            add(ModelGroup(name = groupName, modelIds = ids))
        }
    }
}

private fun Throwable.toModelFetchMessage(
    http404HintText: String,
    authHintText: String,
    networkHintText: String,
    genericHintText: String,
): String {
    val details = message?.trim().orEmpty()
    val normalized = details.lowercase()

    return when {
        normalized.contains("404") -> http404HintText
        normalized.contains("401") || normalized.contains("403") -> authHintText
        normalized.contains("api key") &&
                (normalized.contains("empty") || normalized.contains("blank") || normalized.contains("required") || details.contains(
                    "\u4E0D\u80FD\u4E3A\u7A7A"
                )) -> authHintText

        normalized.contains("timeout") ||
                normalized.contains("unable to resolve host") ||
                normalized.contains("unknownhost") ||
                normalized.contains("failed to connect") ||
                normalized.contains("connection reset") -> networkHintText

        else -> genericHintText
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderInstanceDropdown(
    providers: List<ProviderConfig>,
    selectedProviderId: Int,
    onSelect: (Int) -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    modifier: Modifier = Modifier,
) {
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    SettingsDropdownField(
        valueText = selectedProvider?.name.orEmpty(),
        label = stringResource(R.string.provider_dropdown_label),
        items = providers,
        onSelect = { provider -> onSelect(provider.id) },
        itemText = { it.name },
        modifier = modifier,
        placeholder = stringResource(R.string.provider_dropdown_placeholder),
        shape = shape,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelIdDropdown(
    modelIds: List<String>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    shape: Shape = MaterialTheme.shapes.large,
    modifier: Modifier = Modifier,
) {
    val enabled = modelIds.isNotEmpty()
    SettingsDropdownField(
        valueText = selectedModelId,
        label = stringResource(R.string.model_dropdown_label),
        items = modelIds,
        onSelect = onSelect,
        itemText = { it },
        modifier = modifier,
        enabled = enabled,
        placeholder =
            if (enabled) {
                stringResource(R.string.model_dropdown_placeholder)
            } else {
                stringResource(R.string.model_dropdown_empty_placeholder)
            },
        shape = shape,
    )
}









