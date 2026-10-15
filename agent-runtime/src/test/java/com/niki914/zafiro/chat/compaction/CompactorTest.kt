// 保护：Compactor 调度器触发判定、Pi 对齐序列化截断、网络故障降级与用户取消“无事发生”契约
package com.niki914.zafiro.chat.compaction

import com.niki914.okia.Okia
import com.niki914.okia.OkiaConfig
import com.niki914.okia.TurnOptions
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.text.SummaryBlock
import com.niki914.zafiro.chat.util.SilentLoggerRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CompactorTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private fun user(text: String): Message.User =
        Message.User(listOf(ContentBlock.Text(text)))

    private fun assistant(text: String, toolCalls: List<ContentBlock.ToolCall> = emptyList()): Message.Assistant =
        Message.Assistant(
            AssistantMessage(content = listOf(ContentBlock.Text(text)) + toolCalls)
        )

    private fun toolResult(callId: String, name: String, content: String): Message.ToolResult =
        Message.ToolResult(
            callId = callId,
            toolName = name,
            outcome = ToolCallOutcome.Success(content = content)
        )

    private class FakeSession(initialHistory: List<Message>) : Okia {
        val appended = mutableListOf<Message>()
        private val conversationFlow = MutableStateFlow(
            Conversation(
                id = "test-session",
                leafId = null,
                history = initialHistory.mapIndexed { idx, msg ->
                    MessageEntry(id = "e-$idx", timestamp = 0L, message = msg)
                }
            )
        )
        override val conversation: StateFlow<Conversation> = conversationFlow.asStateFlow()
        override val events: SharedFlow<TurnEvent> = MutableSharedFlow()

        override suspend fun append(message: Message): ConversationEntry {
            appended.add(message)
            val newEntry = ConversationEntry(
                id = "entry-${appended.size}",
                parentId = null,
                message = message,
                timestamp = System.currentTimeMillis()
            )
            val newMsgEntry = MessageEntry(
                id = newEntry.id,
                timestamp = newEntry.timestamp,
                message = message
            )
            conversationFlow.value = conversationFlow.value.copy(
                history = conversationFlow.value.history + newMsgEntry
            )
            return newEntry
        }

        override suspend fun send(
            text: String,
            images: List<ContentBlock.Image>,
            options: TurnOptions?,
            onEvent: suspend (TurnEvent) -> Unit,
        ): TurnResult = error("not used")

        override suspend fun stop() = error("not used")
        override suspend fun rewind(entryId: String) = error("not used")
        override suspend fun export(): SessionSnapshot = error("not used")
        override suspend fun update(block: OkiaConfig.Builder.() -> Unit) = error("not used")
        override suspend fun config(): OkiaConfig = error("not used")
        override suspend fun refreshMcpTools(): McpRefreshResult = error("not used")
        override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot = error("not used")
        override suspend fun close() = Unit
    }

    @Test
    fun `cancellation during summarization rethrows and leaves session untouched`() = runTest {
        val initialHistory = listOf(user("u1"), assistant("a1"))
        val session = FakeSession(initialHistory)

        val compactor = Compactor(
            summaryGenerator = { _, _ -> throw CancellationException("user cancelled") }
        )

        try {
            compactor.compact(session, force = true)
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("user cancelled", e.message)
        }

        // 核心不变量：用户取消时无事发生，session 未被 append 任何节点
        assertEquals(0, session.appended.size)
        assertEquals(initialHistory, session.conversation.value.history.map { it.message })
    }

    @Test
    fun `serializes conversation with tool truncation and thinking block omission`() {
        val largeOutput = "A".repeat(3000)
        val history = listOf(
            user("Please analyze log"),
            Message.Assistant(
                AssistantMessage(
                    content = listOf(
                        ContentBlock.Thinking("Hidden internal reasoning chain"),
                        ContentBlock.Text("Analyzing now..."),
                        ContentBlock.ToolCall(
                            id = "call_1",
                            name = "read_log",
                            argumentsJson = """{"path":"/var/log"}"""
                        )
                    )
                )
            ),
            toolResult("call_1", "read_log", largeOutput)
        )

        val serialized = Compactor.serializeConversation(history, maxToolResultChars = 2000)

        // 验证思考块被完全剔除
        assertFalse(serialized.contains("Hidden internal reasoning chain"))
        // 验证助手文本保留
        assertTrue(serialized.contains("[Assistant]: Analyzing now..."))
        // 验证工具调用保留
        assertTrue(serialized.contains("[Assistant tool calls]: read_log({\"path\":\"/var/log\"})"))
        // 验证大工具结果被截断至 2000 字符并附加注记
        assertTrue(serialized.contains("[Tool result]: " + "A".repeat(2000)))
        assertTrue(serialized.contains("[... 1000 more characters truncated]"))
    }

    @Test
    fun `network error during summarization degrades gracefully returning false`() = runTest {
        val initialHistory = listOf(user("u1"), assistant("a1"))
        val session = FakeSession(initialHistory)

        val compactor = Compactor(
            summaryGenerator = { _, _ -> throw IOException("503 Service Unavailable") }
        )

        val result = compactor.compact(session, force = true)

        assertFalse(result)
        assertEquals(0, session.appended.size)
        assertEquals(initialHistory, session.conversation.value.history.map { it.message })
    }

    @Test
    fun `force compaction ignores token threshold`() = runTest {
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
        )
        val session = FakeSession(history)

        var generatorCalled = false
        val compactor = Compactor(
            summaryGenerator = { _, _ ->
                generatorCalled = true
                "## Goal\nSummarized via force"
            }
        )

        // contextTokens 极小，远未达到阈值，但 force = true
        val result = compactor.compact(
            session = session,
            force = true,
            contextTokens = 100L,
            contextWindow = 128000L,
        )

        assertTrue(result)
        assertTrue(generatorCalled)
        assertEquals(1, session.appended.size)

        val appendedUser = session.appended.single() as Message.User
        val text = appendedUser.content.filterIsInstance<ContentBlock.Text>().single().text
        val stripped = SummaryBlock.strip(text)
        assertEquals(2, stripped.payload?.keepTurns)
        assertEquals("## Goal\nSummarized via force", stripped.payload?.summary)
    }

    @Test
    fun `threshold compaction triggers when context exceeds window occupancy threshold`() = runTest {
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
        )
        val session = FakeSession(history)

        var generatorCalled = false
        val compactor = Compactor(
            summaryGenerator = { _, _ ->
                generatorCalled = true
                "## Goal\nThreshold summarized"
            },
            thresholdRatio = 0.70f,
        )

        // contextTokens (7500) >= contextWindow (10000) * 0.70 (7000)
        val result = compactor.compact(
            session = session,
            force = false,
            contextTokens = 7500L,
            contextWindow = 10000L,
        )

        assertTrue(result)
        assertTrue(generatorCalled)
        assertEquals(1, session.appended.size)
    }

    @Test
    fun `does not compact when below threshold and not forced`() = runTest {
        val history = listOf(user("u1"), assistant("a1"))
        val session = FakeSession(history)

        var generatorCalled = false
        val compactor = Compactor(
            summaryGenerator = { _, _ ->
                generatorCalled = true
                "## Goal\nSummary"
            },
            thresholdRatio = 0.70f,
        )

        // contextTokens (6500) < contextWindow (10000) * 0.70 (7000)
        val result = compactor.compact(
            session = session,
            force = false,
            contextTokens = 6500L,
            contextWindow = 10000L,
        )

        assertFalse(result)
        assertFalse(generatorCalled)
        assertEquals(0, session.appended.size)
    }

    @Test
    fun `does not compact when threshold is zero or disabled`() = runTest {
        val history = listOf(user("u1"), assistant("a1"))
        val session = FakeSession(history)

        var generatorCalled = false
        val compactor = Compactor(
            summaryGenerator = { _, _ ->
                generatorCalled = true
                "## Goal\nSummary"
            },
            thresholdRatio = 0f,
        )

        val result = compactor.compact(
            session = session,
            force = false,
            contextTokens = 9999L,
            contextWindow = 10000L,
        )

        assertFalse(result)
        assertFalse(generatorCalled)
        assertEquals(0, session.appended.size)
    }

    @Test
    fun `incorporates previous summary in update prompt`() = runTest {
        val oldSummaryBlock = SummaryBlock.block(
            SummaryBlock.Payload(keepTurns = 2, summary = "## Goal\nOld objective")
        )
        val history = listOf(
            user(oldSummaryBlock),
            user("New user instruction"),
            assistant("New assistant reply"),
        )
        val session = FakeSession(history)

        var capturedUserPrompt: String? = null
        val compactor = Compactor(
            summaryGenerator = { _, userPrompt ->
                capturedUserPrompt = userPrompt
                "## Goal\nUpdated objective"
            }
        )

        val result = compactor.compact(session, force = true)

        assertTrue(result)
        assertTrue(capturedUserPrompt != null)
        assertTrue(capturedUserPrompt!!.contains("<previous-summary>"))
        assertTrue(capturedUserPrompt!!.contains("## Goal\nOld objective"))
        assertTrue(capturedUserPrompt!!.contains("[User]: New user instruction"))
    }
}
