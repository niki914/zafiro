// 保护：历史会话上下文按 keepTurns 倒数裁剪的纯逻辑；多轮保留、工具调用闭环、无压缩透传、滚动多重压缩与边界截断不得错位或截断
package com.niki914.zafiro.chat.compaction

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.text.SummaryBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPrunerTest {

    private fun user(text: String): Message.User =
        Message.User(listOf(ContentBlock.Text(text)))

    private fun summary(keepTurns: Int, text: String = "Summary text"): Message.User =
        user(SummaryBlock.block(SummaryBlock.Payload(keepTurns = keepTurns, summary = text)))

    private fun assistant(text: String, toolCalls: List<ContentBlock.ToolCall> = emptyList()): Message.Assistant =
        Message.Assistant(
            AssistantMessage(
                content = listOf(ContentBlock.Text(text)) + toolCalls
            )
        )

    private fun toolResult(callId: String, name: String, content: String = "ok"): Message.ToolResult =
        Message.ToolResult(
            callId = callId,
            toolName = name,
            outcome = ToolCallOutcome.Success(content = content)
        )

    @Test
    fun `no summary block leaves history untouched`() {
        val history = listOf(
            user("hi"),
            assistant("hello"),
            user("how are you"),
            assistant("good"),
        )
        val result = ContextPruner.prune(history)
        assertFalse(result.hasCompacted)
        assertEquals(0, result.prunedCount)
        assertEquals(history, result.messages)
    }

    @Test
    fun `keepTurns zero drops all messages before summary`() {
        val summaryMsg = summary(keepTurns = 0)
        val history = listOf(
            user("u1"),
            assistant("a1"),
            user("u2"),
            assistant("a2"),
            summaryMsg,
            user("u3"),
            assistant("a3"),
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(4, result.prunedCount)
        assertEquals(listOf(summaryMsg, history[5], history[6]), result.messages)
    }

    @Test
    fun `keepTurns two preserves exactly two recent turns before summary`() {
        val summaryMsg = summary(keepTurns = 2)
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            user("u3"), assistant("a3"),
            user("u4"), assistant("a4"),
            user("u5"), assistant("a5"),
            summaryMsg,
            user("u6"),
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(6, result.prunedCount)
        val expected = listOf(
            summaryMsg,
            history[6], history[7], // u4, a4
            history[8], history[9], // u5, a5
            history[11],           // u6
        )
        assertEquals(expected, result.messages)
    }

    @Test
    fun `preserves multi step tool call chain integrity in kept turns`() {
        val summaryMsg = summary(keepTurns = 1)
        val toolCall1 = ContentBlock.ToolCall(id = "c1", name = "read_file", argumentsJson = "{}")
        val toolCall2 = ContentBlock.ToolCall(id = "c2", name = "run_shell", argumentsJson = "{}")
        val u2 = user("u2")
        val a2Calls = assistant("calling tools", listOf(toolCall1, toolCall2))
        val tr1 = toolResult("c1", "read_file", "file contents")
        val tr2 = toolResult("c2", "run_shell", "done")
        val a2Final = assistant("final answer")

        val history = listOf(
            user("u1"), assistant("a1"),
            u2, a2Calls, tr1, tr2, a2Final,
            summaryMsg,
            user("u3"),
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(2, result.prunedCount)
        val expected = listOf(
            summaryMsg,
            u2, a2Calls, tr1, tr2, a2Final,
            history.last(),
        )
        assertEquals(expected, result.messages)
    }

    @Test
    fun `cascading compaction prunes prior summaries and older messages`() {
        val summary1 = summary(keepTurns = 1, text = "First summary")
        val summary2 = summary(keepTurns = 1, text = "Second summary")
        val history = listOf(
            user("u1"), assistant("a1"),
            summary1,
            user("u2"), assistant("a2"),
            summary2,
            user("u3"), assistant("a3"),
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(3, result.prunedCount) // u1, a1, summary1
        val expected = listOf(
            summary2,
            history[3], history[4], // u2, a2
            history[6], history[7], // u3, a3
        )
        assertEquals(expected, result.messages)
    }

    @Test
    fun `insufficient turns before summary keeps all available turns gracefully`() {
        val summaryMsg = summary(keepTurns = 5)
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            summaryMsg,
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(0, result.prunedCount)
        val expected = listOf(summaryMsg, history[0], history[1], history[2], history[3])
        assertEquals(expected, result.messages)
    }

    @Test
    fun `summary at the end of history leaves subsequent messages empty`() {
        val summaryMsg = summary(keepTurns = 1)
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            summaryMsg,
        )
        val result = ContextPruner.prune(history)
        assertTrue(result.hasCompacted)
        assertEquals(2, result.prunedCount) // u1, a1 pruned
        assertEquals(listOf(summaryMsg, history[2], history[3]), result.messages)
    }
}
