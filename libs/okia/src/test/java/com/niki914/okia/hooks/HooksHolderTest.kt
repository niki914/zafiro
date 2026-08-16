package com.niki914.okia.hooks

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * holder write 契约测试（T5）：字段只读暴露，写入走 write 并记录
 * lastWriter；多次 write 后者覆盖、lastWriter 为最后写入者。
 * 公开面可观察行为（改值 + 签名），不依赖实现内部结构。
 */
class HooksHolderTest {

    private fun snapshot() = RequestSnapshot(
        endpoint = "https://api.test/v1",
        apiKey = "k",
        model = "m",
        systemPrompt = null,
        temperature = 0.7f,
        maxTokens = 100,
        headers = emptyMap(),
        timeouts = HttpTimeouts(1_000, 1_000, 1_000),
        tools = emptyList()
    )

    private fun history(text: String) = listOf<Message>(Message.User(listOf(ContentBlock.Text(text))))

    private fun request(url: String = "https://api.test/v1") = HttpRequest(
        url = url,
        method = "POST",
        headers = emptyMap(),
        body = null,
        timeouts = HttpTimeouts(1_000, 1_000, 1_000)
    )

    private val descriptor = ToolDescriptor("tool", "desc", null, ToolKind.Local)

    // ── InputHolder ────────────────────────────────────────────────────────

    @Test
    fun inputHolderInitialState() {
        val holder = InputHolder("original")
        assertEquals("original", holder.text)
        assertNull(holder.lastWriter)
    }

    @Test
    fun inputHolderWriteUpdatesTextAndWriter() {
        val holder = InputHolder("original")
        holder.write("rewritten", "hook-a")
        assertEquals("rewritten", holder.text)
        assertEquals("hook-a", holder.lastWriter)
    }

    @Test
    fun inputHolderRepeatedWriteLastOneWins() {
        val holder = InputHolder("original")
        holder.write("first", "hook-a")
        holder.write("second", "hook-b")
        assertEquals("second", holder.text)
        assertEquals("hook-b", holder.lastWriter)
    }

    // ── SerializationHolder ────────────────────────────────────────────────

    @Test
    fun serializationHolderInitialState() {
        val snap = snapshot()
        val hist = history("hi")
        val holder = SerializationHolder(snap, hist)
        assertSame(snap, holder.snapshot)
        assertSame(hist, holder.history)
        assertNull(holder.lastWriter)
    }

    @Test
    fun serializationHolderWriteUpdatesAll() {
        val holder = SerializationHolder(snapshot(), history("hi"))
        val newSnap = snapshot().copy(endpoint = "https://other.test/v1")
        val newHist = history("rewritten")

        holder.write(newSnap, newHist, "hook-a")

        assertEquals("https://other.test/v1", holder.snapshot.endpoint)
        assertEquals(newHist, holder.history)
        assertEquals("hook-a", holder.lastWriter)
    }

    // ── HttpRequestHolder ──────────────────────────────────────────────────

    @Test
    fun httpRequestHolderInitialState() {
        val req = request()
        val holder = HttpRequestHolder(req)
        assertSame(req, holder.request)
        assertNull(holder.lastWriter)
    }

    @Test
    fun httpRequestHolderWriteUpdatesRequest() {
        val holder = HttpRequestHolder(request("https://api.test/v1"))
        val newReq = request("https://redacted.test/v1")

        holder.write(newReq, "hook-a")

        assertEquals("https://redacted.test/v1", holder.request.url)
        assertEquals("hook-a", holder.lastWriter)
    }

    // ── ToolCallHolder ─────────────────────────────────────────────────────

    @Test
    fun toolCallHolderInitialState() {
        val holder = ToolCallHolder("call-1", "tool", "{}", descriptor)
        assertEquals("call-1", holder.id)
        assertEquals("tool", holder.name)
        assertEquals("{}", holder.argumentsJson)
        assertSame(descriptor, holder.descriptor)
        assertNull(holder.outcome)
        assertNull(holder.lastWriter)
    }

    @Test
    fun toolCallHolderWriteUpdatesArguments() {
        val holder = ToolCallHolder("call-1", "tool", "{}", descriptor)
        holder.write("{\"x\": 1}", "hook-a")
        assertEquals("{\"x\": 1}", holder.argumentsJson)
        assertEquals("hook-a", holder.lastWriter)
    }

    @Test
    fun toolCallHolderWriteOutcomeRecordsOutcome() {
        val holder = ToolCallHolder("call-1", "tool", "{}", descriptor)
        val outcome = ToolCallOutcome.Success("ok")
        holder.writeOutcome(outcome, "hook-a")
        assertSame(outcome, holder.outcome)
        assertEquals("hook-a", holder.lastWriter)
    }

    @Test
    fun toolCallHolderWriteThenWriteOutcomeKeepsArguments() {
        val holder = ToolCallHolder("call-1", "tool", "{}", descriptor)
        holder.write("{\"x\": 1}", "hook-a")
        val outcome = ToolCallOutcome.Intercepted("denied", isError = true)
        holder.writeOutcome(outcome, "hook-b")

        // 参数改写与阻断互不影响：args 保留改写值，outcome 为阻断结果，lastWriter 为最后写入者
        assertEquals("{\"x\": 1}", holder.argumentsJson)
        assertSame(outcome, holder.outcome)
        assertEquals("hook-b", holder.lastWriter)
    }

    // ── ToolResultHolder ───────────────────────────────────────────────────

    @Test
    fun toolResultHolderInitialState() {
        val outcome = ToolCallOutcome.Success("ok")
        val holder = ToolResultHolder(outcome)
        assertSame(outcome, holder.outcome)
        assertNull(holder.lastWriter)
    }

    @Test
    fun toolResultHolderWriteUpdatesOutcome() {
        val holder = ToolResultHolder(ToolCallOutcome.Success("original"))
        val replaced = ToolCallOutcome.Failure("rewritten")
        holder.write(replaced, "hook-a")
        assertSame(replaced, holder.outcome)
        assertEquals("hook-a", holder.lastWriter)
    }
}
