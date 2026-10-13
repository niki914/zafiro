// 保护：消息级增量持久化器的落盘契约——只追新条、parentId 链、按会话隔离、
// 重复观察幂等、错误回合半条也落、已删会话不杀 collector；
// 以及 `updated_at` 只在生命周期边界（等待审批 / 回合结束）刷新，增量落盘不碰它。
package com.niki914.zafiro.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.util.SilentLoggerRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

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
        assertEquals(
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("partial")))),
            last
        )
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
    fun persistNow_leavesUpdatedAtUntouched() = runTest {
        val initialTime = 1000L
        val sessionId = ConversationRepo.createConversation("session-1", "hi", now = initialTime)

        ConversationPersister.persistNow(conversationOf(sessionId, "u1", "a1"))

        val record = ConversationRepo.getConversation(sessionId)!!
        assertEquals(initialTime, record.summary.updatedAt)
    }

    @Test
    fun trackActivity_bumpsUpdatedAtOnApprovalAndTurnEnd() = runTest {
        val initialTime = 1000L
        val sessionId = ConversationRepo.createConversation("session-1", "hi", now = initialTime)
        val status = MutableStateFlow<AgentState>(AgentState.Idle())
        val touched = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ConversationPersister.trackActivity(scope, status) {
            touched.incrementAndGet()
            sessionId
        }

        status.value = AgentState.Generating("a")
        status.value = AgentState.WaitingApproval(approvalRequest())
        status.value = AgentState.Idle(lastOutcome = TurnOutcome.Completed)

        awaitUpdatedAtAbove(sessionId, initialTime)
        repeat(50) {
            if (touched.get() >= 2) return@repeat
            delay(10)
        }
        assertEquals(2, touched.get())
        scope.cancel()
    }

    @Test
    fun trackActivity_ignoresIdleWithoutTurnOutcome() = runTest {
        // 启动、载入会话、discard 都落回不带 outcome 的空闲态：不是边界。
        // 否则打开一个旧会话就会把它顶到列表最前。
        val sessionId = ConversationRepo.createConversation("session-1", "hi")
        val status = MutableStateFlow<AgentState>(AgentState.Idle())
        val touched = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ConversationPersister.trackActivity(scope, status) {
            touched.incrementAndGet()
            sessionId
        }

        status.value = AgentState.Generating("a")
        status.value = AgentState.Idle()
        status.value = AgentState.ToolRunning(toolName = "t", label = "t", argumentsJson = null)
        status.value = AgentState.Idle()
        status.value = AgentState.WaitingApproval(approvalRequest())

        assertEquals(1, touched.get())
        scope.cancel()
    }

    @Test
    fun deletedSessionSnapshotDoesNotKillCollector() = runTest {
        // 问题 5 修复：会话已删导致的外键违规被隔离——collector 不灭，
        // 后续会话仍正常落盘。
        val deletedId = ConversationRepo.createConversation("deleted", "x")
        val liveId = ConversationRepo.createConversation("live", "x")
        ConversationRepo.deleteConversation(deletedId) // row 已删 → 后续 insert 撞 FK

        val source = Channel<Conversation?>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ConversationPersister.start(scope, source.receiveAsFlow())

        source.send(conversationOf(deletedId, "d1")) // 撞 FK → 隔离，collector 不灭
        source.send(conversationOf(liveId, "l1")) // 正常落盘（collector 若死则永不落盘）

        // 轮询等待 live 落盘（Room I/O 在真实线程；轮询须在真实调度器上，
        // runTest 的虚拟时间不会推进 delay）
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (ConversationRepo.countEntries(liveId) < 1) delay(10)
            }
        }
        assertEquals(1, ConversationRepo.countEntries(liveId))

        scope.cancel()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun approvalRequest(): ApprovalRequest = ApprovalRequest.ToolExecution(
        toolName = "run",
        command = "rm -rf /",
        ruleName = "confirm",
        matchedPattern = "rm -rf",
    )

    /** 轮询等待：Room 写入在真实线程上，runTest 的虚拟时间不推进 delay。 */
    private suspend fun awaitUpdatedAtAbove(sessionId: String, timestamp: Long) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while ((ConversationRepo.getConversation(sessionId)?.summary?.updatedAt
                        ?: 0L) <= timestamp
                ) {
                    delay(10)
                }
            }
        }
    }

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

    private fun linearEntries(vararg messages: Message): List<ConversationEntry> {
        var parent: String? = null
        return messages.mapIndexed { index, message ->
            val entry = ConversationEntry(
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
