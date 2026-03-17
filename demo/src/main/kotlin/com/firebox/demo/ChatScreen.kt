package com.firebox.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firebox.client.FireBoxClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val client = remember { FireBoxClient.getInstance(context) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(client) }
    val uiState by viewModel.uiState.collectAsState()
    val canSend = uiState.isConnected && !uiState.isLoading && uiState.selectedModel != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FireBox Demo")
                        Text(
                            text = if (uiState.isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isConnected) Color.Green else Color.Red
                        )
                    }
                },
                actions = {
                    ModelSelector(
                        selectedModel = uiState.selectedModel,
                        availableModels = uiState.availableModels,
                        onModelSelected = viewModel::selectModel,
                        enabled = uiState.isConnected && !uiState.isLoading,
                        modelsLoaded = uiState.modelsLoaded
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No models available",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Open FireBox to add a provider and configure routing rules.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val intent = context.packageManager
                            .getLaunchIntentForPackage("com.firebox.android")
                        if (intent != null) context.startActivity(intent)
                    }) {
                        Text("Open FireBox")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.refreshModels() }) {
                        Text("Refresh")
                    }
                }
            } else {
                MessageList(
                    messages = uiState.messages,
                    modifier = Modifier.weight(1f)
                )
            }

            if (uiState.error != null) {
                Text(
                    text = "Error: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }

            MessageInput(
                onSendMessage = viewModel::sendMessage,
                enabled = canSend,
                modifier = Modifier.imePadding()
            )
        }
    }
}

@Composable
fun ModelSelector(
    selectedModel: String?,
    availableModels: List<String>,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
    modelsLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = modifier) {
        if (modelsLoaded && availableModels.isEmpty()) {
            TextButton(
                onClick = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage("com.firebox.android")
                    if (intent != null) {
                        context.startActivity(intent)
                    }
                }
            ) {
                Text(
                    text = "Configure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled && availableModels.isNotEmpty()
            ) {
                Text(
                    text = selectedModel ?: "Select model",
                    style = MaterialTheme.typography.bodySmall
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatUiMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = message.content.ifEmpty { "..." },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (message.isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(4.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun MessageInput(
    onSendMessage: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
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
                disabledIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(24.dp)
        )

        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            enabled = enabled && text.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send"
            )
        }
    }
}
