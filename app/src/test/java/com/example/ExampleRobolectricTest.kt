package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chat.data.local.AppDatabase
import com.example.chat.data.local.ChatMessageEntity
import com.example.chat.data.local.ConversationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Local AI Chat", appName)
    }

    @Test
    fun `insert conversation and messages persists in Room database`() = runBlocking {
        val dao = database.chatDao()

        // 1. Create a conversation
        val conversationId = dao.insertConversation(
            ConversationEntity(title = "Test Conversation")
        )

        // 2. Insert messages (User and AI)
        dao.insertMessage(
            ChatMessageEntity(
                conversationId = conversationId,
                sender = ChatMessageEntity.SENDER_USER,
                content = "Hello local storage!"
            )
        )
        dao.insertMessage(
            ChatMessageEntity(
                conversationId = conversationId,
                sender = ChatMessageEntity.SENDER_AI,
                content = "Hello! Your message is safely stored in SQLite."
            )
        )

        // 3. Verify conversation retrieval
        val savedConversations = dao.getAllConversations().first()
        assertEquals(1, savedConversations.size)
        assertEquals("Test Conversation", savedConversations[0].title)

        // 4. Verify messages retrieval
        val messages = dao.getMessagesForConversation(conversationId).first()
        assertEquals(2, messages.size)
        assertEquals("Hello local storage!", messages[0].content)
        assertEquals(ChatMessageEntity.SENDER_USER, messages[0].sender)
        assertEquals("Hello! Your message is safely stored in SQLite.", messages[1].content)
        assertEquals(ChatMessageEntity.SENDER_AI, messages[1].sender)
    }
}
