package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "test-conversation.db"

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
    fun createAndGet_persistsConversationMetadata() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hello world")

        val record = ConversationRepo.getConversation(id)!!
        assertEquals("session-1", record.summary.id)
        assertEquals("hello world", record.summary.title)
        assertFalse(record.summary.titleEdited)
        assertEquals(0, record.summary.turnCount)
        assertEquals("hello world", record.summary.lastMessagePreview)
        assertTrue(record.snapshot.entries.isEmpty())
        assertNull(record.snapshot.leafId)
    }

    @Test
    fun insertEntries_roundTripsMessageTreeExactly() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.ToolResult("c1", "search", ToolCallOutcome.Success("result")),
        )
        ConversationRepo.insertEntries(id, entries)
        ConversationRepo.updateLeafId(id, entries.last().id)

        val snapshot = ConversationRepo.getConversation(id)!!.snapshot
        assertEquals(3, snapshot.entries.size)
        snapshot.entries.zip(entries).forEach { (actual, expected) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.parentId, actual.parentId)
            assertEquals(expected.timestamp, actual.timestamp)
            assertEquals(expected.message, actual.message)
        }
        assertEquals(entries.last().id, snapshot.leafId)
    }

    @Test
    fun insertEntries_isIdempotentByEntryId() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(id, entries)
        ConversationRepo.insertEntries(id, entries)

        assertEquals(2, ConversationRepo.countEntries(id))
        assertEquals(2, ConversationRepo.getConversation(id)!!.snapshot.entries.size)
    }

    @Test
    fun getConversation_missingLeafIdFallsBackToLastEntry() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(id, entries)
        // 不调用 updateLeafId：leaf_id 保持 null，读取时应回退到最后一条（D16 绕坑）

        val snapshot = ConversationRepo.getConversation(id)!!.snapshot
        assertEquals(entries.last().id, snapshot.leafId)
    }

    @Test
    fun forkConversation_copiesTruncatedSubtreeWithForkTitle() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.User(listOf(ContentBlock.Text("u2"))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val newId = ConversationRepo.forkConversation(
            sourceId = sourceId,
            keepEntryCount = 2,
            kind = ForkKind.Fork,
        )

        val newRecord = ConversationRepo.getConversation(newId)!!
        assertTrue(newRecord.summary.title.startsWith("Fork ·"))
        assertTrue(newRecord.summary.titleEdited)
        assertEquals(2, newRecord.snapshot.entries.size)
        assertEquals(entries[0].id, newRecord.snapshot.entries[0].id)
        assertEquals(entries[1].id, newRecord.snapshot.entries[1].id)
        assertEquals(entries[1].id, newRecord.snapshot.leafId)
        // 源会话不受影响
        assertEquals(3, ConversationRepo.countEntries(sourceId))
    }

    @Test
    fun forkConversation_regenerateUsesRegenerateTitle() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(Message.User(listOf(ContentBlock.Text("u1"))))
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val newId = ConversationRepo.forkConversation(
            sourceId = sourceId,
            keepEntryCount = 1,
            kind = ForkKind.Regenerate,
        )

        assertTrue(ConversationRepo.getConversation(newId)!!.summary.title.startsWith("Regenerate ·"))
    }

    @Test
    fun updateDraftAndRename_mutateConversationMetadata() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        ConversationRepo.updateDraft(id, "draft text")
        ConversationRepo.renameConversation(id, "new title")

        val record = ConversationRepo.getConversation(id)!!
        assertEquals("draft text", record.draftText)
        assertEquals("new title", record.summary.title)
        assertTrue(record.summary.titleEdited)
    }

    @Test
    fun deleteConversation_hardDeletesRecord() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(Message.User(listOf(ContentBlock.Text("q1"))))
        ConversationRepo.insertEntries(id, entries)

        ConversationRepo.deleteConversation(id)

        assertNull(ConversationRepo.getConversation(id))
    }

    @Test
    fun updateConversationMetadata_updatesPreviewAndCount() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        ConversationRepo.updateConversationMetadata(
            conversationId = id,
            updatedAt = 1234L,
            lastMessagePreview = "preview",
            turnCount = 5,
        )

        val record = ConversationRepo.getConversation(id)!!
        assertEquals(1234L, record.summary.updatedAt)
        assertEquals("preview", record.summary.lastMessagePreview)
        assertEquals(5, record.summary.turnCount)
    }

    private fun linearEntries(vararg messages: Message): List<ConversationEntry> {
        var parent: String? = null
        return messages.mapIndexed { index, message ->
            val entry = ConversationEntry(
                id = "entry-$index",
                parentId = parent,
                timestamp = 1000L + index,
                message = message,
            )
            parent = entry.id
            entry
        }
    }
}
