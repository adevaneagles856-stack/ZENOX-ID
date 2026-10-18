package com.example.chat.data.repository

import com.example.chat.data.api.GeminiApiClient
import com.example.chat.data.local.ChatDao
import com.example.chat.data.local.ChatMessageEntity
import com.example.chat.data.local.ConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class StorageStats(
    val conversationCount: Int,
    val totalMessageCount: Int
)

class ChatRepository(
    private val chatDao: ChatDao,
    private val apiClient: GeminiApiClient = GeminiApiClient()
) {

    val allConversations: Flow<List<ConversationEntity>> = chatDao.getAllConversations()

    fun getConversation(id: Long): Flow<ConversationEntity?> = chatDao.getConversationById(id)

    fun getMessages(conversationId: Long): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun getOrCreateFirstConversation(): Long = withContext(Dispatchers.IO) {
        val count = chatDao.getConversationCount()
        if (count == 0) {
            return@withContext chatDao.createConversationWithInitialMessage(
                title = "Welcome Chat",
                initialMessage = "Hello! 👋 I'm your AI assistant with on-device local storage. All our conversations are saved in SQLite and will persist across reloads and app restarts.\n\nHow can I help you today?"
            )
        } else {
            val convs = chatDao.getAllConversations()
            // We can pick the first existing conversation ID
            val list = chatDao.getAllConversationsOnce()
            return@withContext list.firstOrNull()?.id ?: chatDao.insertConversation(
                ConversationEntity(title = "New Chat")
            )
        }
    }

    suspend fun createNewConversation(title: String = "New Chat"): Long = withContext(Dispatchers.IO) {
        chatDao.insertConversation(
            ConversationEntity(
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun renameConversation(conversationId: Long, newTitle: String) = withContext(Dispatchers.IO) {
        chatDao.updateConversationTitle(conversationId, newTitle)
    }

    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        chatDao.deleteConversationById(conversationId)
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        chatDao.deleteAllConversations()
        chatDao.deleteAllMessages()
    }

    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(
            conversationCount = chatDao.getConversationCount(),
            totalMessageCount = chatDao.getTotalMessageCount()
        )
    }

    suspend fun sendUserMessage(conversationId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val userMsg = ChatMessageEntity(
            conversationId = conversationId,
            sender = ChatMessageEntity.SENDER_USER,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        val id = chatDao.insertMessage(userMsg)

        // Automatically auto-name conversation if title is still "New Chat"
        val conv = chatDao.getConversationByIdOnce(conversationId)
        if (conv != null && (conv.title == "New Chat" || conv.title == "Welcome Chat")) {
            val autoTitle = text.take(30).trim()
            if (autoTitle.isNotBlank()) {
                chatDao.updateConversationTitle(conversationId, autoTitle)
            }
        }
        chatDao.updateConversationTimestamp(conversationId)
        id
    }

    suspend fun generateAndSaveAiResponse(conversationId: Long, userPrompt: String): String = withContext(Dispatchers.IO) {
        val history = chatDao.getMessagesForConversationOnce(conversationId)
        val aiText = apiClient.generateChatResponse(history, userPrompt)

        val aiMsg = ChatMessageEntity(
            conversationId = conversationId,
            sender = ChatMessageEntity.SENDER_AI,
            content = aiText,
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertMessage(aiMsg)
        chatDao.updateConversationTimestamp(conversationId)
        aiText
    }
}
