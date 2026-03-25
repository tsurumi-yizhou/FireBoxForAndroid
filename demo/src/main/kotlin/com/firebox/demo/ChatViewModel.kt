package com.firebox.demo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firebox.client.FireBoxClient
import com.firebox.client.model.FireBoxMediaFormat
import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxMessageAttachment
import com.firebox.client.model.FireBoxModelInfo
import com.firebox.client.model.FireBoxReasoningEffort
import com.firebox.client.model.FireBoxStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

private const val QUICK_TOOL_VIRTUAL_MODEL_ID = "__quick_tool__"
private const val MODEL_LOAD_MAX_ATTEMPTS = 6
private const val MODEL_LOAD_RETRY_DELAY_MS = 500L

@Serializable
private data class TitleInput(val conversation: String)

@Serializable
private data class TitleOutput(val title: String)

@Serializable
data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val reasoningContent: String? = null,
    val isReasoningExpanded: Boolean = false,
    val attachments: List<ChatUiAttachment> = emptyList(),
    val errorMessage: String? = null,
    val isStreaming: Boolean = false,
)

@Serializable
data class ChatUiAttachment(
    val mediaFormat: String,
    val mimeType: String,
    val filePath: String,
    val fileName: String,
)

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableModels: List<FireBoxModelInfo> = emptyList(),
    val selectedModel: String? = null,
    val selectedReasoningEffort: FireBoxReasoningEffort? = null,
    val modelsLoaded: Boolean = false,
) {
    val activeConversation: Conversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    val messages: List<ChatUiMessage>
        get() = activeConversation?.messages ?: emptyList()

    val selectedModelInfo: FireBoxModelInfo?
        get() = availableModels.firstOrNull { it.virtualModelId == selectedModel }
}

class ChatViewModel(
    private val client: FireBoxClient,
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageIdCounter = 0L
    private var currentStreamingMessageId: Long? = null
    private var currentUserMessageId: Long? = null
    private var loadModelsJob: Job? = null

    init {
        client.addConnectionListener(object : FireBoxClient.ConnectionListener {
            override fun onConnected() {
                _uiState.update { it.copy(isConnected = true) }
                loadAvailableModels()
            }

            override fun onDisconnected() {
                _uiState.update { it.copy(isConnected = false) }
            }
        })

        viewModelScope.launch {
            client.connect()
        }

        viewModelScope.launch {
            val saved = repository.loadAll()
            if (saved.isNotEmpty()) {
                val maxMsgId = saved.flatMap { it.messages }.maxOfOrNull { it.id } ?: 0L
                messageIdCounter = maxMsgId
                _uiState.update {
                    it.copy(
                        conversations = saved,
                        activeConversationId = saved.first().id,
                    )
                }
            } else {
                createConversation()
            }
        }
    }

    private fun loadAvailableModels() {
        loadModelsJob?.cancel()
        loadModelsJob =
            viewModelScope.launch(Dispatchers.IO) {
                var availableModels: List<FireBoxModelInfo> = emptyList()
                var resolved = false

                for (attempt in 0 until MODEL_LOAD_MAX_ATTEMPTS) {
                    try {
                        val models = client.listModels()
                        Log.d("ChatViewModel", "listModels returned: $models (attempt=${attempt + 1})")
                        if (models != null) {
                            availableModels = models.filter { it.available }
                            Log.d("ChatViewModel", "Available model IDs: ${availableModels.map { it.virtualModelId }}")

                            // FireBox service snapshot may still be warming up right after connection.
                            // Retry a few times before concluding there are no available models.
                            if (availableModels.isNotEmpty() || attempt == MODEL_LOAD_MAX_ATTEMPTS - 1) {
                                resolved = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Failed to load models (attempt=${attempt + 1})", e)
                    }

                    if (attempt < MODEL_LOAD_MAX_ATTEMPTS - 1) {
                        delay(MODEL_LOAD_RETRY_DELAY_MS)
                    }
                }

                if (resolved) {
                    _uiState.update { state ->
                        val selectedModel =
                            state.selectedModel?.takeIf { current ->
                                availableModels.any { it.virtualModelId == current }
                            } ?: availableModels.firstOrNull()?.virtualModelId
                        state.copy(
                            availableModels = availableModels,
                            selectedModel = selectedModel,
                            modelsLoaded = true,
                        )
                    }
                } else {
                    _uiState.update { it.copy(modelsLoaded = true) }
                }
            }
    }

    fun refreshModels() {
        _uiState.update { it.copy(modelsLoaded = false) }
        loadAvailableModels()
    }

    fun selectModel(modelId: String) {
        _uiState.update { it.copy(selectedModel = modelId) }
    }

    fun selectReasoningEffort(effort: FireBoxReasoningEffort?) {
        _uiState.update { it.copy(selectedReasoningEffort = effort) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun createConversation(): String {
        val conversation = Conversation(id = UUID.randomUUID().toString())
        _uiState.update {
            it.copy(
                conversations = listOf(conversation) + it.conversations,
                activeConversationId = conversation.id,
            )
        }
        persistConversation(conversation.id)
        return conversation.id
    }

    fun switchConversation(id: String) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(activeConversationId = id) }
    }

    fun deleteConversation(id: String) {
        _uiState.update { state ->
            val remaining = state.conversations.filter { it.id != id }
            val newActiveId = when {
                state.activeConversationId != id -> state.activeConversationId
                remaining.isNotEmpty() -> remaining.first().id
                else -> null
            }
            state.copy(conversations = remaining, activeConversationId = newActiveId)
        }
        viewModelScope.launch { repository.delete(id) }
        if (_uiState.value.conversations.isEmpty()) {
            createConversation()
        }
    }

    fun sendMessage(
        text: String,
        attachments: List<ChatUiAttachment> = emptyList(),
    ) {
        val model = _uiState.value.selectedModel
        val activeId = _uiState.value.activeConversationId
        if ((text.isBlank() && attachments.isEmpty()) || model == null || activeId == null) return

        val userMessageId = ++messageIdCounter
        val userMessage = ChatUiMessage(
            id = userMessageId,
            role = "user",
            content = text,
            attachments = attachments,
        )

        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == activeId) {
                        conv.copy(messages = conv.messages + userMessage)
                    } else {
                        conv
                    }
                },
                isLoading = true,
                error = null,
            )
        }
        persistConversation(activeId)

        sendConversationMessage(conversationId = activeId, messageId = userMessageId, model = model)
    }

    fun retryMessage(messageId: Long) {
        if (_uiState.value.isLoading) return
        val conversationId = _uiState.value.activeConversationId ?: return
        val model = _uiState.value.selectedModel ?: return
        val conversation = _uiState.value.activeConversation ?: return
        val retryTarget = conversation.resolveRetryTarget(messageId) ?: return
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == conversationId) {
                        val retainedMessages =
                            conv.trimmedForRetry(retryTarget.userMessageId)
                                ?: return@map conv
                        conv.copy(
                            messages = retainedMessages,
                        )
                    } else {
                        conv
                    }
                },
                isLoading = true,
                error = null,
            )
        }
        persistConversation(conversationId)
        sendConversationMessage(conversationId = conversationId, messageId = retryTarget.userMessageId, model = model)
    }

    fun deleteMessage(messageId: Long) {
        val conversationId = _uiState.value.activeConversationId ?: return
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == conversationId) {
                        conv.copy(messages = conv.messages.filterNot { it.id == messageId })
                    } else {
                        conv
                    }
                },
            )
        }
        persistConversation(conversationId)
    }

    fun toggleReasoning(messageId: Long) {
        val conversationId = _uiState.value.activeConversationId ?: return
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == conversationId) {
                        conv.copy(
                            messages =
                                conv.messages.map { msg ->
                                    if (msg.id == messageId) {
                                        msg.copy(isReasoningExpanded = !msg.isReasoningExpanded)
                                    } else {
                                        msg
                                    }
                                },
                        )
                    } else {
                        conv
                    }
                },
            )
        }
        persistConversation(conversationId)
    }

    private fun sendConversationMessage(
        conversationId: String,
        messageId: Long,
        model: String,
    ) {
        val allMessages =
            _uiState.value.activeConversation?.messages?.mapNotNull { message ->
                if (message.errorMessage != null && message.id != messageId) {
                    null
                } else {
                    message.toClientMessage()
                }
            } ?: return

        val request = FireBoxChatRequest(
            virtualModelId = model,
            messages = allMessages,
            reasoningEffort = _uiState.value.selectedReasoningEffort,
        )
        currentUserMessageId = messageId

        viewModelScope.launch {
            try {
                client.streamChat(request).collect { event ->
                    handleStreamEvent(conversationId, event)
                }
            } catch (e: Exception) {
                handleMessageFailure(conversationId, messageId, e.message ?: "Unknown error")
            }
        }
    }

    private fun handleStreamEvent(conversationId: String, event: FireBoxStreamEvent) {
        when (event.type) {
            FireBoxStreamEvent.Type.STARTED -> {
                val assistantMessageId = ++messageIdCounter
                currentStreamingMessageId = assistantMessageId
                val assistantMessage = ChatUiMessage(
                    id = assistantMessageId,
                    role = "assistant",
                    content = "",
                    reasoningContent = null,
                    isReasoningExpanded = true,
                    errorMessage = null,
                    isStreaming = true,
                )
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(messages = conv.messages + assistantMessage)
                            } else {
                                conv
                            }
                        },
                    )
                }
            }

            FireBoxStreamEvent.Type.DELTA -> {
                val delta = event.deltaText ?: return
                val streamingId = currentStreamingMessageId ?: return
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(
                                    messages = conv.messages.map { msg ->
                                        if (msg.id == streamingId) {
                                            msg.copy(content = msg.content + delta)
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            } else {
                                conv
                            }
                        },
                    )
                }
            }

            FireBoxStreamEvent.Type.REASONING_DELTA -> {
                val delta = event.reasoningText ?: return
                val streamingId = currentStreamingMessageId ?: return
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(
                                    messages = conv.messages.map { msg ->
                                        if (msg.id == streamingId) {
                                            msg.copy(
                                                reasoningContent = msg.reasoningContent.orEmpty() + delta,
                                                isReasoningExpanded = true,
                                            )
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            } else {
                                conv
                            }
                        },
                    )
                }
            }

            FireBoxStreamEvent.Type.COMPLETED -> {
                val streamingId = currentStreamingMessageId
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(
                                    messages = conv.messages.map { msg ->
                                        if (msg.id == streamingId) {
                                            msg.copy(
                                                isStreaming = false,
                                                reasoningContent = event.response?.reasoningText ?: msg.reasoningContent,
                                                isReasoningExpanded = false,
                                            )
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            } else {
                                conv
                            }
                        },
                        isLoading = false,
                    )
                }
                currentStreamingMessageId = null
                currentUserMessageId = null
                persistConversation(conversationId)
                generateTitle(conversationId)
            }

            FireBoxStreamEvent.Type.ERROR -> {
                handleMessageFailure(
                    conversationId = conversationId,
                    messageId = currentUserMessageId,
                    errorMessage = event.error?.message ?: "Stream error",
                )
            }

            FireBoxStreamEvent.Type.CANCELLED -> {
                val streamingId = currentStreamingMessageId
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(messages = conv.messages.filterNot { it.id == streamingId })
                            } else {
                                conv
                            }
                        },
                        isLoading = false,
                    )
                }
                currentStreamingMessageId = null
                currentUserMessageId = null
            }

            else -> {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            client.disconnect()
        }
    }

    private fun persistConversation(conversationId: String) {
        val conv = _uiState.value.conversations.firstOrNull { it.id == conversationId } ?: return
        viewModelScope.launch { repository.save(conv) }
    }

    private fun handleMessageFailure(
        conversationId: String,
        messageId: Long?,
        errorMessage: String,
    ) {
        val streamingId = currentStreamingMessageId
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == conversationId) {
                        conv.copy(
                            messages =
                                conv.messages
                                    .filterNot { it.id == streamingId }
                                    .map { msg ->
                                        if (msg.id == messageId) {
                                            msg.copy(errorMessage = errorMessage)
                                        } else {
                                            msg
                                        }
                                    },
                        )
                    } else {
                        conv
                    }
                },
                isLoading = false,
                error = errorMessage,
            )
        }
        currentStreamingMessageId = null
        currentUserMessageId = null
        persistConversation(conversationId)
    }

    private fun generateTitle(conversationId: String) {
        val state = _uiState.value
        val conv = state.conversations.firstOrNull { it.id == conversationId } ?: return
        if (conv.title != "New conversation") return

        val snippet = conv.messages.joinToString("\n") { "${it.role}: ${it.content}" }
            .take(500)
        if (snippet.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result =
                    client.callFunction<TitleInput, TitleOutput>(
                        virtualModelId = QUICK_TOOL_VIRTUAL_MODEL_ID,
                        name = "generate_conversation_title",
                        description =
                            "Generate a concise conversation title in the same language as the conversation. " +
                                "Return plain title text in the title field, under 30 characters, with no quotes.",
                        input = TitleInput(conversation = snippet),
                        temperature = 0.2f,
                        maxOutputTokens = 256,
                    )
                result.error?.let { error ->
                    Log.w("ChatViewModel", "Title generation failed: ${error.message}")
                    _uiState.update { s ->
                        s.copy(error = "Title generation failed: ${error.message}")
                    }
                    return@launch
                }
                val title = result.response?.output?.title.orEmpty().trim().take(40)
                if (title.isBlank()) {
                    _uiState.update { s ->
                        s.copy(error = "Title generation failed: model returned an empty title")
                    }
                    return@launch
                }
                _uiState.update { s ->
                    s.copy(
                        conversations = s.conversations.map { c ->
                            if (c.id == conversationId) c.copy(title = title) else c
                        },
                    )
                }
                persistConversation(conversationId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Title generation failed", e)
                _uiState.update { s ->
                    s.copy(error = "Title generation failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun ChatUiMessage.toClientMessage(): FireBoxMessage? {
        val mappedAttachments =
            attachments.mapNotNull { attachment ->
                val file = java.io.File(attachment.filePath)
                if (!file.exists()) {
                    Log.w("ChatViewModel", "Attachment missing: ${attachment.filePath}")
                    return@mapNotNull null
                }
                FireBoxMessageAttachment(
                    mediaFormat =
                        when (attachment.mediaFormat) {
                            "image" -> FireBoxMediaFormat.Image
                            "video" -> FireBoxMediaFormat.Video
                            "audio" -> FireBoxMediaFormat.Audio
                            else -> FireBoxMediaFormat.Image
                        },
                    mimeType = attachment.mimeType,
                    filePath = attachment.filePath,
                    fileName = attachment.fileName,
                    sizeBytes = file.length(),
                )
            }
        return FireBoxMessage(
            role = role,
            content = content,
            attachments = mappedAttachments,
        )
    }

    private data class RetryTarget(
        val userMessageId: Long,
    )

    private fun Conversation.resolveRetryTarget(messageId: Long): RetryTarget? {
        val selectedIndex = messages.indexOfFirst { it.id == messageId }
        if (selectedIndex < 0) return null
        val userMessage =
            messages
                .take(selectedIndex + 1)
                .lastOrNull { it.role == "user" }
                ?: return null
        return RetryTarget(userMessageId = userMessage.id)
    }

    private fun Conversation.trimmedForRetry(userMessageId: Long): List<ChatUiMessage>? {
        val targetIndex = messages.indexOfFirst { it.id == userMessageId }
        if (targetIndex < 0) return null
        return messages
            .take(targetIndex + 1)
            .map { message ->
                if (message.id == userMessageId) {
                    message.copy(errorMessage = null)
                } else {
                    message
                }
            }
    }
}
