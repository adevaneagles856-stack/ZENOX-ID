package com.example.chat.data.api

import android.util.Log
import com.example.BuildConfig
import com.example.chat.data.local.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val MODEL_NAME = "gemini-3.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfiguredWithRealKey(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    suspend fun generateChatResponse(
        history: List<ChatMessageEntity>,
        latestUserPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY

        if (isConfiguredWithRealKey()) {
            try {
                return@withContext callGeminiApi(apiKey, history, latestUserPrompt)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API call failed, falling back to local responder", e)
                return@withContext generateLocalFallbackResponse(
                    latestUserPrompt,
                    errorNotice = "⚠️ Cloud Gemini API error (${e.message ?: "Network error"}). Running in Local Mode."
                )
            }
        } else {
            return@withContext generateLocalFallbackResponse(
                latestUserPrompt,
                errorNotice = "ℹ️ Note: Set GEMINI_API_KEY in the Secrets panel to enable live Google Gemini 3.5 Flash. Now running with local intelligent responder."
            )
        }
    }

    private fun callGeminiApi(
        apiKey: String,
        history: List<ChatMessageEntity>,
        latestUserPrompt: String
    ): String {
        val url = "$BASE_URL/$MODEL_NAME:generateContent?key=$apiKey"

        val contentsArray = JSONArray()

        // Include recent conversation turns for context (up to last 10 turns)
        val recentHistory = history.takeLast(10)
        for (msg in recentHistory) {
            val role = if (msg.isFromUser) "user" else "model"
            val turnObj = JSONObject().apply {
                put("role", role)
                val partsArray = JSONArray().apply {
                    put(JSONObject().put("text", msg.content))
                }
                put("parts", partsArray)
            }
            contentsArray.put(turnObj)
        }

        // Add the current prompt if not already in history
        if (recentHistory.none { it.isFromUser && it.content == latestUserPrompt }) {
            val currentTurn = JSONObject().apply {
                put("role", "user")
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", latestUserPrompt))
                }
                put("parts", parts)
            }
            contentsArray.put(currentTurn)
        }

        val systemInstruction = JSONObject().apply {
            val parts = JSONArray().apply {
                put(
                    JSONObject().put(
                        "text",
                        "You are an intelligent, friendly AI assistant. Format answers clearly using markdown headings, bullet points, and code formatting when applicable."
                    )
                )
            }
            put("parts", parts)
        }

        val requestJson = JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", systemInstruction)
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBodyString = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errObj = JSONObject(responseBodyString).optJSONObject("error")
                errObj?.optString("message") ?: "HTTP error ${response.code}"
            } catch (e: Exception) {
                "HTTP error ${response.code}"
            }
            throw RuntimeException(errorMsg)
        }

        val root = JSONObject(responseBodyString)
        val candidates = root.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val firstCandidate = candidates.getJSONObject(0)
            val contentObj = firstCandidate.optJSONObject("content")
            val parts = contentObj?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                val text = parts.getJSONObject(0).optString("text")
                if (text.isNotBlank()) {
                    return text
                }
            }
        }

        return "I received your message, but no response text was generated."
    }

    private fun generateLocalFallbackResponse(prompt: String, errorNotice: String): String {
        val lower = prompt.lowercase().trim()

        val responseBody = when {
            lower.contains("local storage") || lower.contains("room") || lower.contains("persist") || lower.contains("refresh") -> {
                """### 💾 Local Storage System Overview
Your conversation is permanently stored in on-device SQLite via **Android Room Database**.

**Key Persistence Features:**
- **Zero Data Loss:** All messages are saved immediately into the `chat_messages` table with timestamps.
- **Relational Model:** Conversations are indexed with cascade foreign keys to maintain referential integrity.
- **Survives Refreshes & Restarts:** When the app is reloaded, refreshed, or backgrounded, Room rehydrates your full chat history reactively through Kotlin Coroutines `Flow`.
- **Session Management:** You can create new chat threads, switch between previous conversations, rename them, or delete them anytime using the drawer or top bar."""
            }
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") -> {
                """Hello! 👋 I'm your AI assistant with **persistent local storage**.

Any message you send here is saved locally on your device via Room SQLite. You can close the app or restart it, and your full conversation history will still be right here!

What would you like to explore or discuss today?"""
            }
            lower.contains("who are you") || lower.contains("what can you do") -> {
                """I am an AI assistant integrated with local device persistence.

**What I can do:**
1. **Chat & Answer Questions:** Discuss concepts, programming, design, and ideas.
2. **Persistent History:** Automatically save every message in local storage so it never disappears on refresh or restart.
3. **Multi-Session Management:** Organize separate topics into distinct conversations.
4. **Cloud / Local Hybrid:** Seamlessly connect to Google Gemini 3.5 Flash when an API key is configured."""
            }
            lower.contains("code") || lower.contains("kotlin") || lower.contains("compose") -> {
                """Here is how the local storage persistence is implemented in Kotlin with Jetpack Compose & Room:

```kotlin
// Reactive observation of stored messages
val messages by chatViewModel.activeMessages.collectAsStateWithLifecycle()

// Messages are saved suspended in background IO
fun sendMessage(text: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val msg = ChatMessageEntity(
            conversationId = activeConvId,
            sender = "USER",
            content = text,
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertMessage(msg)
    }
}
```

The UI reacts automatically as soon as new messages are inserted into the SQLite database!"""
            }
            else -> {
                """I've received your message:
> *"$prompt"*

Your message has been safely committed to the local database. Here are a few things you can try:
- Ask about **Local Storage** or **Room Database** architecture.
- Request code snippets or technical explanations.
- Tap the **+ New Chat** button in the top bar to create a fresh conversation thread.
- Switch between previous conversations in the history drawer."""
            }
        }

        return "$responseBody\n\n---\n*$errorNotice*"
    }
}
