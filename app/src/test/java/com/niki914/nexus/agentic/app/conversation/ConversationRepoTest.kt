package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.kai.ChatTurn
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationRepoTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        ConversationRepo.init(context)
    }

    @After
    fun tearDown() = runTest {
        ConversationRepo.closeForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun createListAndGet_persistsConversationMetadata() = runTest {
        val id = ConversationRepo.createConversation("  hello conversation  ", now = 10L)

        val summaries = ConversationRepo.listConversations()
        val record = ConversationRepo.getConversation(id)

        assertEquals(1, summaries.size)
        assertEquals(id, summaries.single().id)
        assertEquals("hello conversation", summaries.single().title)
        assertFalse(summaries.single().titleEdited)
        assertEquals(10L, summaries.single().createdAt)
        assertEquals(10L, summaries.single().updatedAt)
        assertEquals("hello conversation", summaries.single().lastMessagePreview)
        assertEquals(0, summaries.single().turnCount)
        assertEquals("", record?.draftText)
        assertEquals(emptyList<ChatTurn>(), record?.history)
    }

    @Test
    fun saveHistory_replacesTurnsAndSkipsSystemTurns() = runTest {
        val id = ConversationRepo.createConversation("first", now = 1L)
        ConversationRepo.saveHistory(
            conversationId = id,
            history = listOf(ChatTurn.User("old"), ChatTurn.Assistant("old answer")),
            now = 2L,
        )

        ConversationRepo.saveHistory(
            conversationId = id,
            history = listOf(
                ChatTurn.System("system"),
                ChatTurn.User("new"),
                ChatTurn.Assistant("new answer"),
            ),
            now = 3L,
        )

        val record = ConversationRepo.getConversation(id)!!
        assertEquals(listOf(ChatTurn.User("new"), ChatTurn.Assistant("new answer")), record.history)
        assertEquals(2, record.summary.turnCount)
        assertEquals(3L, record.summary.updatedAt)
        assertEquals("new answer", record.summary.lastMessagePreview)
    }

    @Test
    fun updateDraftAndRename_mutateConversationMetadata() = runTest {
        val id = ConversationRepo.createConversation("first")

        ConversationRepo.updateDraft(id, "draft")
        ConversationRepo.renameConversation(id, "  renamed  ")

        val record = ConversationRepo.getConversation(id)!!
        assertEquals("draft", record.draftText)
        assertEquals("renamed", record.summary.title)
        assertTrue(record.summary.titleEdited)
    }

    @Test
    fun deleteConversation_hardDeletesRecord() = runTest {
        val id = ConversationRepo.createConversation("first")
        ConversationRepo.saveHistory(id, listOf(ChatTurn.User("hello")))

        ConversationRepo.deleteConversation(id)

        assertNull(ConversationRepo.getConversation(id))
        assertTrue(ConversationRepo.listConversations().isEmpty())
    }

    @Test
    fun saveHistory_missingConversationDoesNotCreateRecord() = runTest {
        ConversationRepo.saveHistory("missing", listOf(ChatTurn.User("hello")))

        assertTrue(ConversationRepo.listConversations().isEmpty())
    }

    private companion object {
        private const val DB_NAME = "conversation_history.db"
    }
}
