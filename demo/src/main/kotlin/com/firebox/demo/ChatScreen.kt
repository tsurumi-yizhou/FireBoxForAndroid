package com.firebox.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.firebox.client.FireBoxClient
import com.firebox.client.model.FireBoxMediaFormat
import com.firebox.client.model.FireBoxModelInfo
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import java.io.File
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
                onRetryMessage = viewModel::retryMessage,
                onDeleteMessage = viewModel::deleteMessage,
                onToggleReasoning = viewModel::toggleReasoning,
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
                onRetryMessage = viewModel::retryMessage,
                onDeleteMessage = viewModel::deleteMessage,
                onToggleReasoning = viewModel::toggleReasoning,
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
    onSendMessage: (String, List<ChatUiAttachment>) -> Unit,
    onRetryMessage: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onToggleReasoning: (Long) -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onDismissError: () -> Unit,
    showMenuButton: Boolean,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    val canSend = uiState.isConnected && !uiState.isLoading && uiState.selectedModel != null
    val supportsImageInput =
        uiState.selectedModelInfo?.capabilities?.inputFormats?.contains(FireBoxMediaFormat.Image) == true
    val snackbarHostState = remember { SnackbarHostState() }

    if (!uiState.isConnected) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LinearProgressIndicator(modifier = Modifier.width(220.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Connecting to FireBox...",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

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
                        Text(uiState.activeConversation?.title ?: "New conversation")
                        Text(
                            text = if (uiState.isConnected) "Connected" else "Connecting",
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
                    onRetryMessage = onRetryMessage,
                    onDeleteMessage = onDeleteMessage,
                    onToggleReasoning = onToggleReasoning,
                    modifier = Modifier.weight(1f),
                )
            }

            MessageInput(
                onSendMessage = onSendMessage,
                enabled = canSend,
                supportsImageInput = supportsImageInput,
                context = context,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String?,
    availableModels: List<FireBoxModelInfo>,
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
                    text = { Text(model.virtualModelId) },
                    onClick = {
                        onModelSelected(model.virtualModelId)
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
    onRetryMessage: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onToggleReasoning: (Long) -> Unit,
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
            MessageBubble(
                message = message,
                onRetry = { onRetryMessage(message.id) },
                onDelete = { onDeleteMessage(message.id) },
                onToggleReasoning = { onToggleReasoning(message.id) },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onToggleReasoning: (() -> Unit)? = null,
) {
    var actionsExpanded by remember(message.id) { mutableStateOf(false) }
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
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            actionsExpanded = true
                        },
                    )
                }
                .padding(12.dp),
        ) {
            if (message.attachments.isNotEmpty()) {
                AttachmentRow(message.attachments)
                if (message.content.isNotBlank() || message.reasoningContent.orEmpty().isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (!message.reasoningContent.isNullOrBlank()) {
                val reasoningContent = message.reasoningContent
                ReasoningBlock(
                    reasoning = reasoningContent,
                    isStreaming = message.isStreaming,
                    expanded = message.isStreaming || message.isReasoningExpanded,
                    onToggle = if (message.isStreaming) null else onToggleReasoning,
                )
                if (message.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (message.content.isEmpty() && message.isStreaming && message.reasoningContent.isNullOrBlank()) {
                Text("...")
            } else if (isUser || message.isStreaming) {
                SelectionContainer {
                    Text(message.content)
                }
            } else {
                SelectionContainer {
                    Markdown(
                        content = message.content,
                        imageTransformer = Coil3ImageTransformerImpl,
                    )
                }
            }
            if (!message.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (message.isStreaming) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.dp,
                )
            }

            DropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
            ) {
                if (onRetry != null) {
                    DropdownMenuItem(
                        text = { Text("Retry") },
                        onClick = {
                            actionsExpanded = false
                            onRetry()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        },
                        enabled = !message.isStreaming,
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            actionsExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        },
                        enabled = !message.isStreaming,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    onSendMessage: (String, List<ChatUiAttachment>) -> Unit,
    enabled: Boolean,
    supportsImageInput: Boolean,
    context: Context,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ChatUiAttachment>>(emptyList()) }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val imported = uri?.let { importImageAttachment(context, it) } ?: return@rememberLauncherForActivityResult
            attachments = attachments + imported
        }

    LaunchedEffect(supportsImageInput) {
        if (!supportsImageInput && attachments.isNotEmpty()) {
            attachments = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            AttachmentRow(
                attachments = attachments,
                onRemove = { attachmentToRemove ->
                    attachments = attachments.filterNot { it.filePath == attachmentToRemove.filePath }
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (supportsImageInput) {
                IconButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Add image",
                    )
                }
            }

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
                    if (text.isNotBlank() || attachments.isNotEmpty()) {
                        onSendMessage(text, attachments)
                        text = ""
                        attachments = emptyList()
                    }
                },
                enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachments: List<ChatUiAttachment>,
    onRemove: ((ChatUiAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentCard(attachment = attachment, onRemove = onRemove)
        }
    }
}

@Composable
private fun AttachmentCard(
    attachment: ChatUiAttachment,
    onRemove: ((ChatUiAttachment) -> Unit)? = null,
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val bitmap = remember(attachment.filePath) { BitmapFactory.decodeFile(attachment.filePath) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    modifier = Modifier.size(96.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                onRemove?.let {
                    IconButton(onClick = { it(attachment) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(
    reasoning: String,
    isStreaming: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (isStreaming) "Thinking" else "Thought process",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onToggle != null) {
                    TextButton(
                        onClick = onToggle,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (expanded) "Collapse" else "Expand")
                    }
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun importImageAttachment(
    context: Context,
    uri: Uri,
): ChatUiAttachment? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "image/jpeg"
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
    val destinationDir = File(context.filesDir, "demo-attachments").also { it.mkdirs() }
    val destination = File(destinationDir, "img-${System.currentTimeMillis()}.$extension")
    resolver.openInputStream(uri)?.use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    return ChatUiAttachment(
        mediaFormat = "image",
        mimeType = mimeType,
        filePath = destination.absolutePath,
        fileName = destination.name,
    )
}
