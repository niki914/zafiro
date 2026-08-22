package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmErrorCode
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode as OkiaLLMErrorCode
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class LlmStreamEventMapperTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    // ── 文本流映射 ────────────────────────────────────────────────────────────

    @Test
    fun `TurnStarted maps to RoundStarted`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnStarted("hello"),
            startedAtMs = 0L,
            defaultErrorMessage = "default error",
        )
        assertEquals(LlmStreamEvent.RoundStarted, result)
    }

    @Test
    fun `TextDelta maps with delta and cumulative fullText from partial`() {
        // 真实序列：先 TextStarted 建立基线，再 TextDelta（delta = partial - 累积）
        LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(content = listOf(ContentBlock.Text("he")))),
            startedAtMs = 0L,
            defaultErrorMessage = "default error",
        )
        val partial = AssistantMessage(content = listOf(ContentBlock.Text("helo")))
        val startedAtMs = System.currentTimeMillis() - 500L
        val result = LlmStreamEventMapper.map(
            TurnEvent.TextDelta(index = 0, delta = "lo", partial = partial),
            startedAtMs = startedAtMs,
            defaultErrorMessage = "default error",
        )
        val delta = result as LlmStreamEvent.TextDelta
        assertEquals("lo", delta.delta)
        assertEquals("helo", delta.fullText)
        // elapsed 500ms → charsPerSecond = 4 * 1000 / 500 = 8
        assertEquals(8f, delta.charsPerSecond!!, 0.001f)
    }

    @Test
    fun `TextStarted maps to full delta and following deltas are incremental`() {
        // OKIA 首 delta 在 TextStarted（不带增量文本）；Mapper 以 partial 全量作 delta
        val started = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("你好")))),
            0L,
            "default error",
        ) as LlmStreamEvent.TextDelta
        assertEquals("你好", started.delta)
        assertEquals("你好", started.fullText)

        // 后续 TextDelta：delta = partial - 已累积（增量），fullText = 累积
        val next = LlmStreamEventMapper.map(
            TurnEvent.TextDelta(0, "！有什么", AssistantMessage(listOf(ContentBlock.Text("你好！有什么")))),
            0L,
            "default error",
        ) as LlmStreamEvent.TextDelta
        assertEquals("！有什么", next.delta)
        assertEquals("你好！有什么", next.fullText)

        // 事件序列 delta 累积 == fullText：UI appendText 逐 delta 追加即得完整结果
        val accumulated = started.delta + next.delta
        assertEquals(next.fullText, accumulated)
    }

    @Test
    fun `TextEnded resets accumulation for next block`() {
        LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("first")))),
            0L,
            "default error",
        )
        assertNull(LlmStreamEventMapper.map(TurnEvent.TextEnded(0, "first", AssistantMessage(emptyList())), 0L, "default error"))

        // 下一块从新基线开始：TextStarted 全量，不带上一块残留
        val next = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("second")))),
            0L,
            "default error",
        ) as LlmStreamEvent.TextDelta
        assertEquals("second", next.delta)
    }

    @Test
    fun `TurnCompleted resets accumulation across turns`() {
        LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("answer1")))),
            0L,
            "default error",
        )
        LlmStreamEventMapper.map(
            TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("answer1")))),
            0L,
            "default error",
        )

        val next = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("answer2")))),
            0L,
            "default error",
        ) as LlmStreamEvent.TextDelta
        assertEquals("answer2", next.delta)
        assertEquals("answer2", next.fullText)
    }

    // ── 工具执行映射（T2 铺路：事件当前不会出现，映射逻辑先行） ────────────────

    @Test
    fun `ToolRunning maps with tool call identity`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolRunning(0, call, AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        val running = result as LlmStreamEvent.ToolRunning
        assertEquals("c1", running.call.callId)
        assertEquals("search", running.call.name)
    }

    @Test
    fun `ToolSucceeded outcome Success maps to ToolSucceeded`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(0, call, ToolCallOutcome.Success("payload"), AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        val succeeded = result as LlmStreamEvent.ToolSucceeded
        assertEquals("payload", succeeded.outputText)
    }

    @Test
    fun `ToolSucceeded outcome Intercepted without error maps to ToolSucceeded`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(0, call, ToolCallOutcome.Intercepted("cached", "payload"), AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        assertEquals(LlmStreamEvent.ToolSucceeded::class, result!!::class)
    }

    @Test
    fun `ToolSucceeded outcome Intercepted with error maps to ToolFailed`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(0, call, ToolCallOutcome.Intercepted("denied", isError = true), AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        val failed = result as LlmStreamEvent.ToolFailed
        assertEquals("denied", failed.message)
    }

    @Test
    fun `ToolSucceeded outcome Failure maps to ToolFailed`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(0, call, ToolCallOutcome.Failure("boom", "detail"), AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        val failed = result as LlmStreamEvent.ToolFailed
        assertEquals("boom", failed.message)
        assertEquals("detail", failed.resultText)
    }

    @Test
    fun `ToolFailed maps message from outcome`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolFailed(0, call, ToolCallOutcome.Failure("failed"), AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        assertEquals("failed", (result as LlmStreamEvent.ToolFailed).message)
    }

    // ── 工具意图阶段不发射（无 UI 消费端） ────────────────────────────────────

    @Test
    fun `ToolCall intent and Thinking and Retry events are dropped`() {
        val partial = AssistantMessage(emptyList())
        val call = ContentBlock.ToolCall("c", "t", "{}")
        val events = listOf(
            TurnEvent.ToolCallStarted(0, partial),
            TurnEvent.ToolCallDelta(0, "{}", partial),
            TurnEvent.ToolCallReady(0, call, partial),
            TurnEvent.ThinkingStarted(0, partial),
            TurnEvent.ThinkingDelta(0, "th", partial),
            TurnEvent.ThinkingEnded(0, "th", partial),
            TurnEvent.RetryScheduled(1, 3, 100L, "rate limit"),
        )
        events.forEach {
            assertNull(LlmStreamEventMapper.map(it, 0L, "default error"))
        }
    }

    // ── 终态映射 ──────────────────────────────────────────────────────────────

    @Test
    fun `TurnCompleted maps to Completed with full text`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnCompleted(AssistantMessage(content = listOf(ContentBlock.Text("answer")))),
            0L,
            "default error",
        )
        assertEquals(LlmStreamEvent.Completed("answer"), result)
    }

    @Test
    fun `TurnFailed maps to Error with mapped code`() {
        val error = LLMError(OkiaLLMErrorCode.Transport, "boom")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        val mapped = result as LlmStreamEvent.Error
        assertEquals("boom", mapped.message)
        assertEquals(LlmErrorCode.Transport, mapped.code)
    }

    @Test
    fun `TurnFailed maps Auth to LlmErrorCode Auth`() {
        val error = LLMError(OkiaLLMErrorCode.Auth, "invalid key")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        assertEquals(LlmErrorCode.Auth, (result as LlmStreamEvent.Error).code)
    }

    @Test
    fun `TurnFailed maps ContextOverflow to Parse`() {
        val error = LLMError(OkiaLLMErrorCode.ContextOverflow, "context too long")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        assertEquals(LlmErrorCode.Parse, (result as LlmStreamEvent.Error).code)
    }

    @Test
    fun `TurnFailed with blank message falls back to default`() {
        val error = LLMError(OkiaLLMErrorCode.Auth, " ")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        assertEquals("default error", (result as LlmStreamEvent.Error).message)
    }

    @Test
    fun `TurnIdleTimeout maps to Error with default message and Transport code`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnIdleTimeout(AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        val mapped = result as LlmStreamEvent.Error
        assertEquals("default error", mapped.message)
        assertEquals(LlmErrorCode.Transport, mapped.code)
    }

    @Test
    fun `TurnAborted does not produce an error event`() {
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.TurnAborted(AssistantMessage(emptyList()), StopCause.UserStop),
                0L,
                "default error",
            )
        )
    }
}