package com.firebox.demo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firebox.client.FireBoxClient
import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class TitleInput(val conversation: String)

@Serializable
private data class TitleOutput(val title: String)

@Serializable
data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
)

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String? = null,
    val modelsLoaded: Boolean = false,
) {
    val activeConversation: Conversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    val messages: List<ChatUiMessage>
        get() = activeConversation?.messages ?: emptyList()
}

class ChatViewModel(
    private val client: FireBoxClient,
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageIdCounter = 0L
    private var currentStreamingMessageId: Long? = null

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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models = client.listModels()
                Log.d("ChatViewModel", "listModels returned: $models")
                if (models != null) {
                    val modelIds = models.filter { it.available }.map { it.virtualModelId }
                    Log.d("ChatViewModel", "Available model IDs: $modelIds")
                    _uiState.update {
                        it.copy(availableModels = modelIds, modelsLoaded = true)
                    }
                } else {
                    _uiState.update { it.copy(modelsLoaded = true) }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load models", e)
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

    fun sendMessage(text: String) {
        val model = _uiState.value.selectedModel
        val activeId = _uiState.value.activeConversationId
        if (text.isBlank() || model == null || activeId == null) return

        val userMessageId = ++messageIdCounter
        val userMessage = ChatUiMessage(
            id = userMessageId,
            role = "user",
            content = text,
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

        val allMessages = _uiState.value.activeConversation?.messages?.map { msg ->
            FireBoxMessage(role = msg.role, content = msg.content)
        } ?: return

        val request = FireBoxChatRequest(
            virtualModelId = model,
            messages = allMessages,
        )

        viewModelScope.launch {
            try {
                client.streamChat(request).collect { event ->
                    handleStreamEvent(activeId, event)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error",
                    )
                }
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

            FireBoxStreamEvent.Type.COMPLETED -> {
                val streamingId = currentStreamingMessageId
                _uiState.update { state ->
                    state.copy(
                        conversations = state.conversations.map { conv ->
                            if (conv.id == conversationId) {
                                conv.copy(
                                    messages = conv.messages.map { msg ->
                                        if (msg.id == streamingId) {
                                            msg.copy(isStreaming = false)
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
                persistConversation(conversationId)
                generateTitle(conversationId)
            }

            FireBoxStreamEvent.Type.ERROR -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = event.error?.message ?: "Stream error",
                    )
                }
                currentStreamingMessageId = null
            }

            FireBoxStreamEvent.Type.CANCELLED -> {
                _uiState.update { it.copy(isLoading = false) }
                currentStreamingMessageId = null
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

    private fun generateTitle(conversationId: String) {
        val state = _uiState.value
        val model = state.selectedModel ?: return
        val conv = state.conversations.firstOrNull { it.id == conversationId } ?: return
        if (conv.title != "New conversation") return

        val snippet = conv.messages.joinToString("\n") { "${it.role}: ${it.content}" }
            .take(500)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val titleRequest = FireBoxChatRequest(
                    virtualModelId = model,
                    messages = listOf(
                        FireBoxMessage(
                            role = "user",
                            content = "Generate a short title (under 30 chars) for this conversation. Reply with ONLY the title, no quotes or extra text. Use the same language as the conversation.\n\n$snippet",
                        ),
                    ),
                    maxOutputTokens = 64,
                )
                val sb = StringBuilder()
                client.streamChat(titleRequest).collect { event ->
                    if (event.type == FireBoxStreamEvent.Type.DELTA) {
                        sb.append(event.deltaText.orEmpty())
                    }
                }
                val title = sb.toString().trim().take(40)
                if (title.isBlank()) return@launch
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
            }
        }
    }
}
