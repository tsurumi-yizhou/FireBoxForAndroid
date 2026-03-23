package com.firebox.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
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
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.firebox.client.FireBoxClient
import com.firebox.client.model.FireBoxMediaFormat
import com.firebox.client.model.FireBoxModelInfo
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
private sealed interface DemoNavKey : NavKey {
    @Serializable
    data object Chat : DemoNavKey

    @Serializable
    data object Conversations : DemoNavKey
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val client = remember { FireBoxClient.getInstance(context) }
    val repository = remember { ConversationRepository(context) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(client, repository) }
    val uiState by viewModel.uiState.collectAsState()
    val backStack = rememberNavBackStack(DemoNavKey.Chat)

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )

    LaunchedEffect(isExpanded) {
        if (backStack.isEmpty()) {
            backStack.add(DemoNavKey.Chat)
            return@LaunchedEffect
        }
        if (isExpanded && backStack.lastOrNull() == DemoNavKey.Conversations) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    val listPane: @Composable () -> Unit = {
        ConversationListPane(
            conversations = uiState.conversations,
            activeConversationId = uiState.activeConversationId,
            onConversationSelected = { id ->
                viewModel.switchConversation(id)
                if (!isExpanded && backStack.size > 1 && backStack.lastOrNull() == DemoNavKey.Conversations) {
                    backStack.removeAt(backStack.lastIndex)
                }
            },
            onNewConversation = {
                viewModel.createConversation()
                if (!isExpanded && backStack.size > 1 && backStack.lastOrNull() == DemoNavKey.Conversations) {
                    backStack.removeAt(backStack.lastIndex)
                }
            },
            onDeleteConversation = viewModel::deleteConversation,
        )
    }

    val chatPane: @Composable (Boolean) -> Unit = { showMenuButton ->
        ChatDetailPane(
            uiState = uiState,
            onSendMessage = viewModel::sendMessage,
            onRetryMessage = viewModel::retryMessage,
            onDeleteMessage = viewModel::deleteMessage,
            onToggleReasoning = viewModel::toggleReasoning,
            onSelectModel = viewModel::selectModel,
            onRefreshModels = viewModel::refreshModels,
            onDismissError = viewModel::dismissError,
            showMenuButton = showMenuButton,
            onMenuClick = {
                if (backStack.lastOrNull() != DemoNavKey.Conversations) {
                    backStack.add(DemoNavKey.Conversations)
                }
            },
        )
    }

    val navDisplay: @Composable (Boolean, Modifier) -> Unit = { showListAsEntry, modifier ->
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                } else {
                    activity?.finish()
                }
            },
            entryProvider = entryProvider {
                entry<DemoNavKey.Chat> {
                    chatPane(showListAsEntry)
                }
                entry<DemoNavKey.Conversations> {
                    if (showListAsEntry) {
                        listPane()
                    } else {
                        chatPane(false)
                    }
                }
            },
        )
    }

    if (isExpanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                listPane()
            }
            navDisplay(false, Modifier.weight(1f).fillMaxSize())
        }
    } else {
        navDisplay(true, Modifier.fillMaxSize())
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
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )

    if (!uiState.isConnected) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.width(220.dp))
                    Text(
                        text = "Connecting to FireBox...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        return
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        onDismissError()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
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
                        Text(
                            text = uiState.activeConversation?.title ?: "New conversation",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                ChatContextBar(
                    selectedModel = uiState.selectedModel,
                    availableModels = uiState.availableModels,
                    supportsImageInput = supportsImageInput,
                    messageCount = uiState.messages.size,
                    onModelSelected = onSelectModel,
                    enabled = uiState.isConnected && !uiState.isLoading,
                    modelsLoaded = uiState.modelsLoaded,
                )

                if (uiState.modelsLoaded && uiState.availableModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "No models available",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Please configure at least one provider with available models in the FireBox app.",
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = onRefreshModels) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Refresh")
                                }
                            }
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
}

@Composable
private fun ChatContextBar(
    selectedModel: String?,
    availableModels: List<FireBoxModelInfo>,
    supportsImageInput: Boolean,
    messageCount: Int,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
    modelsLoaded: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelSelector(
                selectedModel = selectedModel,
                availableModels = availableModels,
                onModelSelected = onModelSelected,
                enabled = enabled,
                modelsLoaded = modelsLoaded,
            )

            Text(
                text = if (supportsImageInput) {
                    "$messageCount messages  •  image input on"
                } else {
                    "$messageCount messages"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
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
    val selectedModelLabel = selectedModel?.substringAfterLast("/") ?: "Select model"

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
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = selectedModelLabel,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
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

    if (messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = "Start with a quick prompt like:\n\"Summarize this codebase architecture\"",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val actionAlignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp)
    }
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = actionAlignment,
        ) {
            Surface(
                color = backgroundColor,
                shape = bubbleShape,
                tonalElevation = if (isUser) 2.dp else 1.dp,
                border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (message.attachments.isNotEmpty()) {
                        AttachmentRow(message.attachments)
                        if (message.content.isNotBlank() || message.reasoningContent.orEmpty().isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
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
                            Spacer(Modifier.height(10.dp))
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
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Text(
                                text = "Generating...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (onRetry != null || onDelete != null) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = !message.isStreaming,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            enabled = !message.isStreaming,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (attachments.isNotEmpty()) {
            AttachmentRow(
                attachments = attachments,
                onRemove = { attachmentToRemove ->
                    attachments = attachments.filterNot { it.filePath == attachmentToRemove.filePath }
                },
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (supportsImageInput) {
                    FilledTonalIconButton(
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
                    placeholder = { Text("Type your message...") },
                    enabled = enabled,
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )

                FilledIconButton(
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
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val bitmap = remember(attachment.filePath) { BitmapFactory.decodeFile(attachment.filePath) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

