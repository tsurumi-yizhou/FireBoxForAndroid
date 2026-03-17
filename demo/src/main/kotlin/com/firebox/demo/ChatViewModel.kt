package com.firebox.demo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firebox.client.FireBoxClient
import com.firebox.client.model.FireBoxChatRequest
import com.firebox.client.model.FireBoxMessage
import com.firebox.client.model.FireBoxStreamEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String? = null,
    val modelsLoaded: Boolean = false,
)

class ChatViewModel(private val client: FireBoxClient) : ViewModel() {

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
    }

    private fun loadAvailableModels() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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

    fun sendMessage(text: String) {
        val model = _uiState.value.selectedModel
        if (text.isBlank() || model == null) return

        val userMessageId = ++messageIdCounter
        val userMessage = ChatUiMessage(
            id = userMessageId,
            role = "user",
            content = text
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        val allMessages = _uiState.value.messages.map { msg ->
            FireBoxMessage(role = msg.role, content = msg.content)
        }

        val request = FireBoxChatRequest(
            virtualModelId = model,
            messages = allMessages
        )

        viewModelScope.launch {
            try {
                client.streamChat(request).collect { event ->
                    handleStreamEvent(event)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun handleStreamEvent(event: FireBoxStreamEvent) {
        when (event.type) {
            FireBoxStreamEvent.Type.STARTED -> {
                val assistantMessageId = ++messageIdCounter
                currentStreamingMessageId = assistantMessageId
                val assistantMessage = ChatUiMessage(
                    id = assistantMessageId,
                    role = "assistant",
                    content = "",
                    isStreaming = true
                )
                _uiState.update {
                    it.copy(messages = it.messages + assistantMessage)
                }
            }

            FireBoxStreamEvent.Type.DELTA -> {
                event.deltaText?.let { delta ->
                    currentStreamingMessageId?.let { streamingId ->
                        _uiState.update { state ->
                            val updatedMessages = state.messages.map { msg ->
                                if (msg.id == streamingId) {
                                    msg.copy(content = msg.content + delta)
                                } else {
                                    msg
                                }
                            }
                            state.copy(messages = updatedMessages)
                        }
                    }
                }
            }

            FireBoxStreamEvent.Type.COMPLETED -> {
                currentStreamingMessageId?.let { streamingId ->
                    _uiState.update { state ->
                        val updatedMessages = state.messages.map { msg ->
                            if (msg.id == streamingId) {
                                msg.copy(isStreaming = false)
                            } else {
                                msg
                            }
                        }
                        state.copy(
                            messages = updatedMessages,
                            isLoading = false
                        )
                    }
                }
                currentStreamingMessageId = null
            }

            FireBoxStreamEvent.Type.ERROR -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = event.error?.message ?: "Stream error"
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
}
