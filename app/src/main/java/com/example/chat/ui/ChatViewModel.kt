package com.example.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chat.data.local.AppDatabase
import com.example.chat.data.local.ChatMessageEntity
import com.example.chat.data.local.ConversationEntity
import com.example.chat.data.repository.ChatRepository
import com.example.chat.data.repository.StorageStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    val conversations: StateFlow<List<ConversationEntity>> = repository.allConversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    val activeConversation: StateFlow<ConversationEntity?> = _activeConversationId.flatMapLatest { id ->
        if (id != null) {
            repository.getConversation(id)
        } else {
            flowOf(null)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val messages: StateFlow<List<ChatMessageEntity>> = _activeConversationId.flatMapLatest { id ->
        if (id != null) {
            repository.getMessages(id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStats(0, 0))
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _userFeedbackMessage = MutableStateFlow<String?>(null)
    val userFeedbackMessage: StateFlow<String?> = _userFeedbackMessage.asStateFlow()

    init {
        initializeFirstConversation()
    }

    private fun initializeFirstConversation() {
        viewModelScope.launch {
            val initialId = repository.getOrCreateFirstConversation()
            _activeConversationId.value = initialId
            refreshStorageStats()
        }
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun selectConversation(id: Long) {
        _activeConversationId.value = id
        refreshStorageStats()
    }

    fun startNewConversation(title: String = "New Chat") {
        viewModelScope.launch {
            val newId = repository.createNewConversation(title)
            _activeConversationId.value = newId
            refreshStorageStats()
        }
    }

    fun sendMessage(customPrompt: String? = null) {
        val promptToSend = customPrompt ?: _inputText.value.trim()
        if (promptToSend.isBlank() || _isGenerating.value) return

        val conversationId = _activeConversationId.value ?: return

        // Clear input field if sending user's typed input
        if (customPrompt == null) {
            _inputText.value = ""
        }

        viewModelScope.launch {
            _isGenerating.value = true
            try {
                // 1. Save user message to Room immediately
                repository.sendUserMessage(conversationId, promptToSend)
                refreshStorageStats()

                // 2. Fetch and persist AI response
                repository.generateAndSaveAiResponse(conversationId, promptToSend)
                refreshStorageStats()
            } catch (e: Exception) {
                _userFeedbackMessage.value = "Failed to complete AI response: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun retryLastMessage() {
        val currentMessages = messages.value
        val lastUserMsg = currentMessages.lastOrNull { it.isFromUser }
        if (lastUserMsg != null && !_isGenerating.value) {
            sendMessage(lastUserMsg.content)
        }
    }

    fun renameActiveConversation(newTitle: String) {
        val convId = _activeConversationId.value ?: return
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            repository.renameConversation(convId, newTitle.trim())
            _userFeedbackMessage.value = "Conversation renamed to \"$newTitle\""
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                // Switch to another conversation or create new
                val remaining = repository.allConversations
                // Find next or create
                val initialId = repository.getOrCreateFirstConversation()
                _activeConversationId.value = initialId
            }
            refreshStorageStats()
            _userFeedbackMessage.value = "Conversation deleted"
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllData()
            val newId = repository.createNewConversation("New Chat")
            _activeConversationId.value = newId
            refreshStorageStats()
            _userFeedbackMessage.value = "All local conversation history cleared"
        }
    }

    fun dismissFeedbackMessage() {
        _userFeedbackMessage.value = null
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            _storageStats.value = repository.getStorageStats()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(application)
            val repository = ChatRepository(db.chatDao())
            return ChatViewModel(application, repository) as T
        }
    }
}
