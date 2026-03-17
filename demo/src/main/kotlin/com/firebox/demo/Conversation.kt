package com.firebox.demo

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New conversation",
    val messages: List<ChatUiMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun meta() = ConversationMeta(id = id, title = title, createdAt = createdAt)
}
