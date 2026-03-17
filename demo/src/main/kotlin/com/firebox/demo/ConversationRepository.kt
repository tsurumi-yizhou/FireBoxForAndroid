package com.firebox.demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ConversationRepository(context: Context) {

    private val dir = File(context.filesDir, "conversations").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.extension == "jsonl" } ?: return@withContext emptyList()
        files.mapNotNull { file ->
            try {
                val lines = file.readLines()
                if (lines.isEmpty()) return@mapNotNull null
                val meta = json.decodeFromString<ConversationMeta>(lines[0])
                val messages = lines.drop(1).map { json.decodeFromString<ChatUiMessage>(it) }
                Conversation(
                    id = meta.id,
                    title = meta.title,
                    messages = messages,
                    createdAt = meta.createdAt,
                )
            } catch (e: Exception) {
                Log.e("ConversationRepo", "Failed to load ${file.name}", e)
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    suspend fun save(conversation: Conversation) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir, "${conversation.id}.jsonl")
            val sb = StringBuilder()
            sb.appendLine(json.encodeToString(ConversationMeta.serializer(), conversation.meta()))
            for (msg in conversation.messages) {
                sb.appendLine(json.encodeToString(ChatUiMessage.serializer(), msg))
            }
            file.writeText(sb.toString())
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            File(dir, "$id.jsonl").delete()
        }
    }
}
