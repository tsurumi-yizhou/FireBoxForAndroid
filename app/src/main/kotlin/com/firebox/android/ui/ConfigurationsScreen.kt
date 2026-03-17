package com.firebox.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.firebox.android.FireBoxGraph
import com.firebox.android.R
import com.firebox.android.ai.ProviderBaseUrlNormalizer
import com.firebox.android.data.ProviderModelSdkFetcher
import com.firebox.android.link.PendingProviderBindRequest
import com.firebox.android.model.*
import kotlinx.coroutines.launch

private const val HttpsSchemePrefix = "https://"

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

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingRoute by remember { mutableStateOf<RouteRule?>(null) }
    val navigator = rememberListDetailPaneScaffoldNavigator<Int>(
        scaffoldDirective =
            calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(
                windowAdaptiveInfo = currentWindowAdaptiveInfo(),
            ).copy(defaultPanePreferredWidth = 400.dp),
    )
    val currentDestination = navigator.currentDestination
    val selectedProviderId =
        currentDestination
            ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
            ?.contentKey
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val isSinglePane = navigator.scaffoldDirective.maxHorizontalPartitions <= 1
    val showDetailAsScreen = isSinglePane && selectedProviderId != null

    BackHandler(enabled = showDetailAsScreen) {
        coroutineScope.launch {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex != 0 && selectedProviderId != null) {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(selectedProviderId, selectedProvider) {
        if (selectedProviderId != null && selectedProvider == null) {
            navigator.navigateBack(BackNavigationBehavior.PopLatest)
        }
    }

    LaunchedEffect(providerBindRequest?.requestId) {
        val request = providerBindRequest ?: return@LaunchedEffect
        selectedTabIndex = 0
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
        onProviderBindRequestConsumed()
    }

    AppScreenScaffold(
        title =
            if (showDetailAsScreen) {
                selectedProvider?.name ?: stringResource(R.string.screen_model_list)
            } else {
                stringResource(R.string.screen_configurations)
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
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = { selectedTabIndex = it },
                        providers = providers,
                        routes = routes,
                        onOpenProviderDetail = { providerId ->
                            coroutineScope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, providerId)
                            }
                        },
                        onAddProvider = {
                            val nextId = (providers.maxOfOrNull { it.id } ?: 0) + 1
                            editingProvider =
                                ProviderConfig(
                                    id = nextId,
                                    type = ProviderType.OpenAI,
                                    name = "Provider #$nextId",
                                    baseUrl = "",
                                    enabled = true,
                                )
                        },
                        onEditProvider = { provider -> editingProvider = provider },
                        onDeleteProvider = { providerId -> coroutineScope.launch { repo.deleteProvider(providerId) } },
                        onAddRoute = {
                            val nextId = (routes.maxOfOrNull { it.id } ?: 0) + 1
                            editingRoute =
                                RouteRule(
                                    id = nextId,
                                    virtualModelId = "",
                                    strategy = RouteStrategy.Failover,
                                    candidates = emptyList(),
                                )
                        },
                        onEditRoute = { rule -> editingRoute = rule },
                        onDeleteRoute = { ruleId -> coroutineScope.launch { repo.deleteRouteRule(ruleId) } },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    if (selectedTabIndex == 0 && selectedProvider != null) {
                        ProviderModelsPane(
                            provider = selectedProvider,
                            onProviderChange = { updated -> coroutineScope.launch { repo.upsertProvider(updated) } },
                            showHeader = !showDetailAsScreen,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            },
        )

        editingProvider?.let { draft ->
            ProviderEditorDialog(
                provider = draft,
                isNew = providers.none { it.id == draft.id },
                onDismiss = { editingProvider = null },
                onSave = { updated ->
                    coroutineScope.launch { repo.upsertProvider(updated) }
                    editingProvider = null
                },
            )
        }

        editingRoute?.let { draft ->
            RouteEditorDialog(
                rule = draft,
                providers = providers,
                isNew = routes.none { it.id == draft.id },
                onDismiss = { editingRoute = null },
                onSave = { updated ->
                    coroutineScope.launch { repo.upsertRouteRule(updated) }
                    editingRoute = null
                },
            )
        }
    }
}

@Composable
private fun ProviderSettings(
    providers: List<ProviderConfig>,
    onAdd: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (Int) -> Unit,
    onManageModels: (Int) -> Unit,
) {
    FilledTonalButton(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.config_add_provider))
    }

    providers.forEach { provider ->
        key(provider.id) {
            ProviderSummaryCard(
                provider = provider,
                onEdit = { onEdit(provider) },
                onDelete = { onDelete(provider.id) },
                onManageModels = { onManageModels(provider.id) },
            )
        }
    }
}

@Composable
private fun ProviderSummaryCard(
    provider: ProviderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManageModels: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = provider.name.ifBlank { stringResource(R.string.config_unnamed_provider) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = provider.type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.config_delete_provider_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = provider.baseUrl.ifBlank { stringResource(R.string.common_placeholder_dash) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.common_edit))
                }
                OutlinedButton(onClick = onManageModels, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.config_manage_models))
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    provider: ProviderConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
) {
    var draft by remember(provider) {
        mutableStateOf(
            provider.copy(
                baseUrl = baseUrlEditorValue(provider.baseUrl),
                apiKey = if (isNew) provider.apiKey else "",
            ),
        )
    }

    SettingsEditorDialog(
        title = stringResource(if (isNew) R.string.config_create_provider_title else R.string.config_edit_provider_title),
        onDismiss = onDismiss,
        onConfirm = {
            onSave(
                draft.copy(
                    baseUrl = normalizeBaseUrlDraft(draft.type, draft.baseUrl),
                    apiKey = draft.apiKey,
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
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
            shape = RoundedCornerShape(12.dp),
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
            shape = RoundedCornerShape(12.dp),
        )
    }
}

@Composable
private fun SettingsEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 820.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeDropdown(
    value: ProviderType,
    onValueChange: (ProviderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.config_provider_type_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProviderType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onValueChange(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteSettings(
    routes: List<RouteRule>,
    onAdd: () -> Unit,
    onEdit: (RouteRule) -> Unit,
    onDelete: (Int) -> Unit,
) {
    FilledTonalButton(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.route_add_rule))
    }

    routes.forEach { rule ->
        key(rule.id) {
            RouteSummaryCard(
                rule = rule,
                onEdit = { onEdit(rule) },
                onDelete = { onDelete(rule.id) },
            )
        }
    }
}

@Composable
private fun RouteSummaryCard(
    rule: RouteRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = rule.virtualModelId.ifBlank { stringResource(R.string.route_virtual_model_rule) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = rule.strategy.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.route_delete_rule_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = stringResource(R.string.route_candidates_count, rule.candidates.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteEditorDialog(
    rule: RouteRule,
    providers: List<ProviderConfig>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
) {
    var draft by remember(rule) { mutableStateOf(rule) }
    val candidateListState = rememberLazyListState()
    val dialogScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    SettingsEditorDialog(
        title = stringResource(if (isNew) R.string.route_create_rule_title else R.string.route_edit_rule_title),
        onDismiss = onDismiss,
        onConfirm = { onSave(draft) },
        scrollState = dialogScrollState,
    ) {
        OutlinedTextField(
            value = draft.virtualModelId,
            onValueChange = { draft = draft.copy(virtualModelId = it) },
            label = { Text(stringResource(R.string.route_match_model_id_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        RouteStrategyDropdown(
            value = draft.strategy,
            onValueChange = { draft = draft.copy(strategy = it) },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val availableProviders = availableProvidersForRoute(draft, providers)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.route_candidates_count, draft.candidates.size),
                style = MaterialTheme.typography.labelLarge,
            )
            FilledTonalButton(
                onClick = {
                    val firstProvider = availableProviders.firstOrNull()
                    val newCandidates =
                        draft.candidates +
                                listOf(
                                    ModelTarget(
                                        providerId = firstProvider?.id ?: 0,
                                        modelId = firstProvider?.enabledModels?.firstOrNull().orEmpty(),
                                    ),
                                )
                    draft = draft.copy(candidates = newCandidates)
                    coroutineScope.launch {
                        dialogScrollState.animateScrollTo(dialogScrollState.maxValue)
                        candidateListState.animateScrollToItem(newCandidates.lastIndex)
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.route_add_target), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (draft.candidates.isEmpty()) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.route_no_target_models),
                        color = MaterialTheme.colorScheme.outline,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                state = candidateListState,
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(draft.candidates) { candidateIndex, target ->
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
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SuggestionChip(
                                    onClick = { },
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.route_target_priority,
                                                candidateIndex + 1
                                            )
                                        )
                                    },
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
                                            contentDescription = stringResource(R.string.route_move_up_cd)
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
                                            contentDescription = stringResource(R.string.route_move_down_cd)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            draft = draft.copy(
                                                candidates = draft.candidates.toMutableList()
                                                    .also { it.removeAt(candidateIndex) })
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
                                        candidates = draft.candidates.toMutableList()
                                            .also { it[candidateIndex] = updatedTarget })
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
                                        candidates = draft.candidates.toMutableList()
                                            .also { it[candidateIndex] = updatedTarget })
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteStrategyDropdown(
    value: RouteStrategy,
    onValueChange: (RouteStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.route_strategy_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RouteStrategy.entries.forEach { strategy ->
                DropdownMenuItem(
                    text = { Text(strategy.displayName) },
                    onClick = {
                        onValueChange(strategy)
                        expanded = false
                    },
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsPane(
    provider: ProviderConfig?,
    onProviderChange: (ProviderConfig) -> Unit,
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
                                    shape = RoundedCornerShape(12.dp),
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
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    providers: List<ProviderConfig>,
    routes: List<RouteRule>,
    onOpenProviderDetail: (Int) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (Int) -> Unit,
    onAddRoute: () -> Unit,
    onEditRoute: (RouteRule) -> Unit,
    onDeleteRoute: (Int) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp)
                    .padding(horizontal = 16.dp),
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { onTabSelected(0) },
                    text = { Text(stringResource(R.string.config_tab_provider)) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { onTabSelected(1) },
                    text = { Text(stringResource(R.string.config_tab_route)) },
                )
            }

            when (selectedTabIndex) {
                0 ->
                    ProviderTabContent(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        providers = providers,
                        navigatorAction = onOpenProviderDetail,
                        onEditProvider = onEditProvider,
                        onDeleteProvider = onDeleteProvider,
                        onAddProvider = onAddProvider,
                    )

                else ->
                    RouteTabContent(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        routes = routes,
                        onAddRoute = onAddRoute,
                        onEditRoute = onEditRoute,
                        onDeleteRoute = onDeleteRoute,
                    )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ProviderTabContent(
    modifier: Modifier = Modifier,
    providers: List<ProviderConfig>,
    navigatorAction: (Int) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (Int) -> Unit,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderSettings(
            providers = providers,
            onAdd = onAddProvider,
            onEdit = onEditProvider,
            onDelete = onDeleteProvider,
            onManageModels = navigatorAction,
        )
    }
}

@Composable
private fun RouteTabContent(
    modifier: Modifier = Modifier,
    routes: List<RouteRule>,
    onAddRoute: () -> Unit,
    onEditRoute: (RouteRule) -> Unit,
    onDeleteRoute: (Int) -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RouteSettings(
            routes = routes,
            onAdd = onAddRoute,
            onEdit = onEditRoute,
            onDelete = onDeleteRoute,
        )
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
    modifier: Modifier = Modifier,
) {
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedProvider?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.provider_dropdown_label)) },
            placeholder = { Text(stringResource(R.string.provider_dropdown_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    onClick = {
                        onSelect(provider.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelIdDropdown(
    modelIds: List<String>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = modelIds.isNotEmpty()

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedModelId,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.model_dropdown_label)) },
            placeholder = {
                Text(
                    if (enabled) {
                        stringResource(R.string.model_dropdown_placeholder)
                    } else {
                        stringResource(R.string.model_dropdown_empty_placeholder)
                    }
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            modelIds.forEach { modelId ->
                DropdownMenuItem(
                    text = { Text(modelId) },
                    onClick = {
                        onSelect(modelId)
                        expanded = false
                    },
                )
            }
        }
    }
}









