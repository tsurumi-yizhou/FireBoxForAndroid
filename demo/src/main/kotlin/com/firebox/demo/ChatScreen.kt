package com.firebox.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.firebox.client.FireBoxClient
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val client = remember { FireBoxClient.getInstance(context) }
    val repository = remember { ConversationRepository(context) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(client, repository) }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )

    val drawerContent: @Composable () -> Unit = {
        ConversationListPane(
            conversations = uiState.conversations,
            activeConversationId = uiState.activeConversationId,
            onConversationSelected = { id ->
                viewModel.switchConversation(id)
            },
            onNewConversation = { viewModel.createConversation() },
            onDeleteConversation = viewModel::deleteConversation,
        )
    }

    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(300.dp)) {
                    drawerContent()
                }
            },
        ) {
            ChatDetailPane(
                uiState = uiState,
                onSendMessage = viewModel::sendMessage,
                onSelectModel = viewModel::selectModel,
                onRefreshModels = viewModel::refreshModels,
                onDismissError = viewModel::dismissError,
                showMenuButton = false,
                onMenuClick = {},
            )
        }
    } else {
        val drawerState = rememberDrawerState(DrawerValue.Closed)

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(300.dp)) {
                    ConversationListPane(
                        conversations = uiState.conversations,
                        activeConversationId = uiState.activeConversationId,
                        onConversationSelected = { id ->
                            viewModel.switchConversation(id)
                            coroutineScope.launch { drawerState.close() }
                        },
                        onNewConversation = {
                            viewModel.createConversation()
                            coroutineScope.launch { drawerState.close() }
                        },
                        onDeleteConversation = viewModel::deleteConversation,
                    )
                }
            },
        ) {
            ChatDetailPane(
                uiState = uiState,
                onSendMessage = viewModel::sendMessage,
                onSelectModel = viewModel::selectModel,
                onRefreshModels = viewModel::refreshModels,
                onDismissError = viewModel::dismissError,
                showMenuButton = true,
                onMenuClick = {
                    coroutineScope.launch { drawerState.open() }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailPane(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onDismissError: () -> Unit,
    showMenuButton: Boolean,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    val canSend = uiState.isConnected && !uiState.isLoading && uiState.selectedModel != null
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        onDismissError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showMenuButton) {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Default.Menu, contentDescription = "Conversations")
                        }
                    }
                },
                title = {
                    Column {
                        Text("FireBox Demo")
                        Text(
                            text = if (uiState.isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isConnected) Color.Green else Color.Red,
                        )
                    }
                },
                actions = {
                    ModelSelector(
                        selectedModel = uiState.selectedModel,
                        availableModels = uiState.availableModels,
                        onModelSelected = onSelectModel,
                        enabled = uiState.isConnected && !uiState.isLoading,
                        modelsLoaded = uiState.modelsLoaded,
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (uiState.modelsLoaded && uiState.availableModels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No models available",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please configure at least one provider with available models in the FireBox app.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onRefreshModels) {
                        Text("Refresh")
                    }
                }
            } else {
                MessageList(
                    messages = uiState.messages,
                    modifier = Modifier.weight(1f),
                )
            }

            MessageInput(
                onSendMessage = onSendMessage,
                enabled = canSend,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String?,
    availableModels: List<String>,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
    modelsLoaded: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    if (!modelsLoaded) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .padding(end = 8.dp),
            strokeWidth = 2.dp,
        )
        return
    }

    if (availableModels.isEmpty()) return

    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text(
                text = selectedModel ?: "Select model",
                style = MaterialTheme.typography.bodySmall,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatUiMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages.reversed(), key = { it.id }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .background(backgroundColor, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            if (message.content.isEmpty() && message.isStreaming) {
                Text("...")
            } else if (isUser || message.isStreaming) {
                Text(message.content)
            } else {
                Markdown(
                    content = message.content,
                    imageTransformer = Coil3ImageTransformerImpl,
                )
            }
            if (message.isStreaming) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.dp,
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    onSendMessage: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            enabled = enabled,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(24.dp),
        )

        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            enabled = enabled && text.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
            )
        }
    }
}
