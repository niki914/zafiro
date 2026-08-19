package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
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
        val partial = AssistantMessage(content = listOf(ContentBlock.Text("hel")))
        val startedAtMs = System.currentTimeMillis() - 500L
        val result = LlmStreamEventMapper.map(
            TurnEvent.TextDelta(index = 0, delta = "lo", partial = partial),
            startedAtMs = startedAtMs,
            defaultErrorMessage = "default error",
        )
        val delta = result as LlmStreamEvent.TextDelta
        assertEquals("lo", delta.delta)
        assertEquals("hel", delta.fullText)
        // elapsed 500ms → charsPerSecond = 3 * 1000 / 500 = 6
        assertEquals(6f, delta.charsPerSecond!!, 0.001f)
    }

    @Test
    fun `TextStarted and TextEnded do not produce events`() {
        val partial = AssistantMessage(content = listOf(ContentBlock.Text("x")))
        assertNull(LlmStreamEventMapper.map(TurnEvent.TextStarted(0, partial), 0L, "default error"))
        assertNull(LlmStreamEventMapper.map(TurnEvent.TextEnded(0, "x", partial), 0L, "default error"))
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
    fun `TurnFailed maps to Error with code null`() {
        val error = LLMError(LLMErrorCode.Transport, "boom")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        val mapped = result as LlmStreamEvent.Error
        assertEquals("boom", mapped.message)
        assertEquals(null, mapped.code)
    }

    @Test
    fun `TurnFailed with blank message falls back to default`() {
        val error = LLMError(LLMErrorCode.Auth, " ")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
            "default error",
        )
        assertEquals("default error", (result as LlmStreamEvent.Error).message)
    }

    @Test
    fun `TurnIdleTimeout maps to Error with default message`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnIdleTimeout(AssistantMessage(emptyList())),
            0L,
            "default error",
        )
        assertEquals("default error", (result as LlmStreamEvent.Error).message)
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