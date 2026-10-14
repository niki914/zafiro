// 保护：beforeSerialization 钩子对上下文裁剪的按需改写契约；无压缩时不触发写操作，含压缩时准确更新 history 且 snapshot 保持原样
package com.niki914.zafiro.chat

import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.zafiro.api.text.SummaryBlock
import com.niki914.zafiro.chat.util.SilentLoggerRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class CompactPruningHookTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private fun user(text: String): Message.User =
        Message.User(listOf(ContentBlock.Text(text)))

    private fun summary(keepTurns: Int, text: String = "Summary text"): Message.User =
        user(SummaryBlock.block(SummaryBlock.Payload(keepTurns = keepTurns, summary = text)))

    private fun assistant(text: String): Message.Assistant =
        Message.Assistant(AssistantMessage(content = listOf(ContentBlock.Text(text))))

    private fun stubSnapshot() = RequestSnapshot(
        endpoint = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "You are a test assistant",
        temperature = 0.5f,
        maxTokens = 2048,
        headers = mapOf("Custom-Header" to "value"),
        timeouts = HttpTimeouts(connectMs = 5000L, readMs = 10000L, writeMs = 5000L),
        tools = emptyList(),
    )

    @Test
    fun `passthrough history when no summary is present`() = runTest {
        val history = listOf(
            user("hello"),
            assistant("hi"),
            user("how are you?"),
            assistant("good"),
        )
        val snapshot = stubSnapshot()
        val holder = SerializationHolder(snapshot, history)

        OkiaSessionAssembler.compactPruningHook.beforeSerialization(holder)

        assertNull(holder.lastWriter)
        assertSame(history, holder.history)
    }

    @Test
    fun `rewrites history and sets lastWriter when summary is present`() = runTest {
        val summaryMsg = summary(keepTurns = 1, text = "Compacted context")
        val history = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            summaryMsg,
            user("u3"),
        )
        val snapshot = stubSnapshot()
        val holder = SerializationHolder(snapshot, history)

        OkiaSessionAssembler.compactPruningHook.beforeSerialization(holder)

        assertEquals("compact_prune", holder.lastWriter)
        val expectedMessages = listOf(
            summaryMsg,
            history[2], history[3], // u2, a2
            history[5],             // u3
        )
        assertEquals(expectedMessages, holder.history)
    }

    @Test
    fun `preserves snapshot untouched during rewrite`() = runTest {
        val summaryMsg = summary(keepTurns = 0)
        val history = listOf(user("u1"), assistant("a1"), summaryMsg)
        val snapshot = stubSnapshot()
        val holder = SerializationHolder(snapshot, history)

        OkiaSessionAssembler.compactPruningHook.beforeSerialization(holder)

        assertEquals("compact_prune", holder.lastWriter)
        assertSame(snapshot, holder.snapshot)
        assertEquals("test-model", holder.snapshot.model)
        assertEquals("You are a test assistant", holder.snapshot.systemPrompt)
    }

    @Test
    fun `original history list is not mutated by hook rewrite`() = runTest {
        val originalHistory = mutableListOf(
            user("u1"), assistant("a1"),
            summary(keepTurns = 1),
            user("u2"),
        )
        val historyCopy = ArrayList(originalHistory)
        val holder = SerializationHolder(stubSnapshot(), originalHistory)

        OkiaSessionAssembler.compactPruningHook.beforeSerialization(holder)

        // holder.history was rewritten
        assertEquals("compact_prune", holder.lastWriter)
        // original list passed in was not modified in-place
        assertEquals(historyCopy, originalHistory)
    }
}
