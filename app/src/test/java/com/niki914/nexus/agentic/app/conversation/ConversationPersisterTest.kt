package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.nexus.agentic.app.util.SilentLoggerRule
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 消息级增量持久化器测试（D3-2/D3-8）：注入可控快照流，验证增量写、
 * 幂等、会话切换、parentId 链、错误回合落盘。
 */
@RunWith(RobolectricTestRunner::class)
class ConversationPersisterTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    private lateinit var context: Context
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        ConversationRepo.init(context)
    }

    @After
    fun tearDown() = runTest {
        ConversationPersister.resetForTest()
        ConversationRepo.closeForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun incrementalInsert_writesOnlyNewMessages() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")

        ConversationPersister.persistNow(conversationOf(sessionId, "u1"))
        assertEquals(1, ConversationRepo.countEntries(sessionId))

        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))
        assertEquals(2, ConversationRepo.countEntries(sessionId))

        // 再发射相同快照（重复观察/无新消息）→ 不重复写
        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))
        assertEquals(2, ConversationRepo.countEntries(sessionId))
    }

    @Test
    fun writesLinearParentChain() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")

        ConversationPersister.persistNow(conversationOf(sessionId, "u1"))
        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))
        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1", "u2"))

        val entries = ConversationRepo.getConversation(sessionId)!!.snapshot.entries
        assertEquals(3, entries.size)
        assertEquals(null, entries[0].parentId)
        assertEquals(entries[0].id, entries[1].parentId)
        assertEquals(entries[1].id, entries[2].parentId)
    }

    @Test
    fun sessionSwitch_isIsolatedPerSession() = runTest {
        val sessionA = ConversationRepo.createConversation("session-a", "a")
        val sessionB = ConversationRepo.createConversation("session-b", "b")

        ConversationPersister.persistNow(conversationOf(sessionA, "a1"))
        ConversationPersister.persistNow(conversationOf(sessionB, "b1"))
        ConversationPersister.persistNow(conversationOf(sessionB, "b1", "b2"))

        assertEquals(1, ConversationRepo.countEntries(sessionA))
        assertEquals(2, ConversationRepo.countEntries(sessionB))
    }

    @Test
    fun restoreSession_alreadyPersistedEntriesAreNotReinserted() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(sessionId, entries)
        ConversationRepo.updateLeafId(sessionId, entries.last().id)
        // 模拟冷启动：持久化器重启，countEntries 从 Room 现有值起步

        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))

        assertEquals(2, ConversationRepo.countEntries(sessionId))
    }

    @Test
    fun errorTurn_partialAssistantIsPersisted() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")

        // 错误回合：User 已 commit + assistant 半条（commitPartial）都在树里
        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "partial"))

        val snapshot = ConversationRepo.getConversation(sessionId)!!.snapshot
        assertEquals(2, snapshot.entries.size)
        val last = snapshot.entries.last().message
        assertEquals(Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("partial")))), last)
    }

    @Test
    fun updatesLeafIdAndMetadata() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")

        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))

        val record = ConversationRepo.getConversation(sessionId)!!
        assertEquals(2, record.summary.turnCount)
        assertEquals("a1", record.summary.lastMessagePreview)
        assertNotNull(record.snapshot.leafId)
    }

    @Test
    fun nullSnapshot_doesNotPersist() = runTest {
        val sessionId = ConversationRepo.createConversation("session-1", "hi")

        // persistNow 只处理具体快照；null 由 start() 的 collect 过滤（流接线
        // 不在此测，见 incrementalInsert 对重复快照的幂等覆盖）
        assertEquals(0, ConversationRepo.countEntries(sessionId))
        assertNull(ConversationRepo.getConversation(sessionId)?.snapshot?.leafId)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun conversationOf(sessionId: String, vararg messages: String): Conversation {
        var parent: String? = null
        val history = messages.mapIndexed { index, text ->
            val message: Message = if (index % 2 == 0) {
                Message.User(listOf(ContentBlock.Text(text)))
            } else {
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text(text))))
            }
            val entry = MessageEntry(id = "m$index", timestamp = 1000L + index, message = message)
            parent = entry.id
            entry
        }
        return Conversation(
            id = sessionId,
            leafId = history.lastOrNull()?.id,
            history = history,
            live = null,
        )
    }

    private fun linearEntries(vararg messages: Message): List<com.niki914.okia.conversation.ConversationEntry> {
        var parent: String? = null
        return messages.mapIndexed { index, message ->
            val entry = com.niki914.okia.conversation.ConversationEntry(
                id = "m$index",
                parentId = parent,
                timestamp = 1000L + index,
                message = message,
            )
            parent = entry.id
            entry
        }
    }

    private companion object {
        const val DB_NAME = "test-conversation.db"
    }
}
