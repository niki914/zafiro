package com.niki914.zafiro.chat.agentic.stream

import com.niki914.okia.error.LLMError
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.okia.error.LLMErrorCode as OkiaLLMErrorCode

class LlmStreamEventMapperTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    // ── 文本流映射 ────────────────────────────────────────────────────────────

    @Test
    fun `TurnStarted maps to RoundStarted`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnStarted("hello"),
            startedAtMs = 0L,
            )
        assertEquals(LlmStreamEvent.RoundStarted, result)
    }

    @Test
    fun `TextDelta maps with delta and cumulative fullText from partial`() {
        // 真实序列：先 TextStarted 建立基线，再 TextDelta（delta = partial - 累积）
        LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(content = listOf(ContentBlock.Text("he")))),
            startedAtMs = 0L,
            )
        val partial = AssistantMessage(content = listOf(ContentBlock.Text("helo")))
        val startedAtMs = System.currentTimeMillis() - 500L
        val result = LlmStreamEventMapper.map(
            TurnEvent.TextDelta(index = 0, delta = "lo", partial = partial),
            startedAtMs = startedAtMs,
            )
        val delta = result as LlmStreamEvent.TextDelta
        assertEquals("lo", delta.delta)
        assertEquals("helo", delta.fullText)
        // elapsed 500ms 基准 → charsPerSecond ≈ 8；壁钟计时有 ms 级漂移，断言容差按范围
        assertTrue(delta.charsPerSecond!! in 7.9f..8.1f)
    }

    @Test
    fun `TextStarted maps to full delta and following deltas are incremental`() {
        // OKIA 首 delta 在 TextStarted（不带增量文本）；Mapper 以 partial 全量作 delta
        val started = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("你好")))),
            0L,
                    ) as LlmStreamEvent.TextDelta
        assertEquals("你好", started.delta)
        assertEquals("你好", started.fullText)

        // 后续 TextDelta：delta = partial - 已累积（增量），fullText = 累积
        val next = LlmStreamEventMapper.map(
            TurnEvent.TextDelta(
                0,
                "！有什么",
                AssistantMessage(listOf(ContentBlock.Text("你好！有什么")))
            ),
            0L,
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
                    )
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.TextEnded(
                    0,
                    "first",
                    AssistantMessage(emptyList())
                ), 0L
            )
        )

        // 下一块从新基线开始：TextStarted 全量，不带上一块残留
        val next = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("second")))),
            0L,
                    ) as LlmStreamEvent.TextDelta
        assertEquals("second", next.delta)
    }

    @Test
    fun `TurnCompleted resets accumulation across turns`() {
        LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("answer1")))),
            0L,
                    )
        LlmStreamEventMapper.map(
            TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("answer1")))),
            0L,
                    )

        val next = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, AssistantMessage(listOf(ContentBlock.Text("answer2")))),
            0L,
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
                    )
        val running = result as LlmStreamEvent.ToolRunning
        assertEquals("c1", running.call.callId)
        assertEquals("search", running.call.name)
    }

    @Test
    fun `ToolSucceeded outcome Success maps to ToolSucceeded`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(
                0,
                call,
                ToolCallOutcome.Success("payload"),
                AssistantMessage(emptyList())
            ),
            0L,
                    )
        val succeeded = result as LlmStreamEvent.ToolSucceeded
        assertEquals("payload", succeeded.outputText)
    }

    @Test
    fun `ToolSucceeded outcome Intercepted without error maps to ToolSucceeded`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(
                0,
                call,
                ToolCallOutcome.Intercepted("cached", "payload"),
                AssistantMessage(emptyList())
            ),
            0L,
                    )
        assertEquals(LlmStreamEvent.ToolSucceeded::class, result!!::class)
    }

    @Test
    fun `ToolSucceeded outcome Intercepted with error maps to ToolFailed`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(
                0,
                call,
                ToolCallOutcome.Intercepted("denied", isError = true),
                AssistantMessage(emptyList())
            ),
            0L,
                    )
        val failed = result as LlmStreamEvent.ToolFailed
        assertEquals("denied", failed.message)
    }

    @Test
    fun `ToolSucceeded outcome Failure maps to ToolFailed`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolSucceeded(
                0,
                call,
                ToolCallOutcome.Failure("boom", "detail"),
                AssistantMessage(emptyList())
            ),
            0L,
                    )
        val failed = result as LlmStreamEvent.ToolFailed
        assertEquals("boom", failed.message)
        assertEquals("detail", failed.resultText)
    }

    @Test
    fun `ToolFailed maps message from outcome`() {
        val call = ContentBlock.ToolCall(id = "c1", name = "search", argumentsJson = "{}")
        val result = LlmStreamEventMapper.map(
            TurnEvent.ToolFailed(
                0,
                call,
                ToolCallOutcome.Failure("failed"),
                AssistantMessage(emptyList())
            ),
            0L,
                    )
        assertEquals("failed", (result as LlmStreamEvent.ToolFailed).message)
    }

    // ── 工具意图阶段：Started 透传占位，Delta/Ready/Retry 丢弃 ────────────────

    @Test
    fun `ToolCallStarted maps to ToolPending and other intent events are dropped`() {
        val partial = AssistantMessage(emptyList())
        val call = ContentBlock.ToolCall("c", "t", "{}")

        val started = LlmStreamEventMapper.map(
            TurnEvent.ToolCallStarted(0, partial, callId = "c1", toolName = "terminal"),
            0L,
                    )
        val pending = started as LlmStreamEvent.ToolPending
        assertEquals("c1", pending.call.callId)
        assertEquals("terminal", pending.call.name)

        val dropped = listOf(
            TurnEvent.ToolCallDelta(0, "{}", partial),
            TurnEvent.ToolCallReady(0, call, partial),
        )
        dropped.forEach {
            assertNull(LlmStreamEventMapper.map(it, 0L))
        }
    }

    @Test
    fun `RetryScheduled maps to Retrying event`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.RetryScheduled(1, 3, 100L, "rate limit"),
            0L,
                    )
        val mapped = result as LlmStreamEvent.Retrying
        assertEquals(1, mapped.attempt)
        assertEquals(3, mapped.maxAttempts)
        assertEquals(100L, mapped.delayMs)
        assertEquals("rate limit", mapped.reason)
    }

    @Test
    fun `TurnFailed with RetryExhausted carries scheduled attempts`() {
        // attempts 来自 RetryScheduled 事件流（结构化），不反向解析错误串
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        LlmStreamEventMapper.map(TurnEvent.RetryScheduled(1, 3, 100L, "transport"), 0L)
        LlmStreamEventMapper.map(TurnEvent.RetryScheduled(2, 3, 100L, "transport"), 0L)
        LlmStreamEventMapper.map(TurnEvent.RetryScheduled(3, 3, 100L, "transport"), 0L)
        val error = LLMError(OkiaLLMErrorCode.RetryExhausted, "retry exhausted (Transport)")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
        )
        val mapped = result as LlmStreamEvent.Error
        assertEquals(LlmErrorCode.RetryExhausted, mapped.code)
        assertEquals(3, mapped.attempts)
    }

    @Test
    fun `TurnFailed without retries has null attempts`() {
        val error = LLMError(OkiaLLMErrorCode.Transport, "connection reset")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
        )
        assertNull((result as LlmStreamEvent.Error).attempts)
    }

    @Test
    fun `RetryScheduled resets text accumulation`() {
        val partial = assistantPartial("hello world")
        LlmStreamEventMapper.map(TurnEvent.TextStarted(0, partial), 0L)

        LlmStreamEventMapper.map(TurnEvent.RetryScheduled(1, 3, 100L, "x"), 0L)

        // 重试后成功尝试的全量重放应完整成为 delta（累积已重置）
        val replay = LlmStreamEventMapper.map(
            TurnEvent.TextStarted(0, assistantPartial("fresh text")),
            0L,
                    ) as LlmStreamEvent.TextDelta
        assertEquals("fresh text", replay.delta)
    }

    // ── 思考块：全量两态，Mapper 分配回合内单调 id ───────────────────────────

    private fun assistantPartial(text: String) =
        AssistantMessage(content = listOf(ContentBlock.Text(text)))

    private fun thinkingPartial(text: String) =
        AssistantMessage(content = listOf(ContentBlock.Thinking(text)))

    @Test
    fun `ThinkingStarted maps to ThinkingStarted with fresh id and full text`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        val result = LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(1, thinkingPartial("deep think")),
            0L,
                    )
        assertEquals(LlmStreamEvent.ThinkingStarted(0, "deep think"), result)
    }

    @Test
    fun `ThinkingDelta re-emits ThinkingStarted with same id and updated full text`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(1, thinkingPartial("dee")),
            0L,
                    )
        val result = LlmStreamEventMapper.map(
            TurnEvent.ThinkingDelta(1, "p", thinkingPartial("deep think")),
            0L,
                    )
        assertEquals(LlmStreamEvent.ThinkingStarted(0, "deep think"), result)
    }

    @Test
    fun `ThinkingEnded maps to ThinkingEnded with final content`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(1, thinkingPartial("deep think")),
            0L,
                    )
        val result = LlmStreamEventMapper.map(
            TurnEvent.ThinkingEnded(1, "deep think", thinkingPartial("deep think")),
            0L,
                    )
        assertEquals(LlmStreamEvent.ThinkingEnded(0, "deep think"), result)
    }

    @Test
    fun `New round reusing same index gets a distinct id (no merge across tool rounds)`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        // 第一轮：index=0 的思考块
        val first = LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(0, thinkingPartial("first block")),
            0L,
                    )
        LlmStreamEventMapper.map(
            TurnEvent.ThinkingEnded(0, "first block", thinkingPartial("first block")),
            0L,
                    )
        // 第二轮（工具轮后 StreamState 重建）：index 又回到 0，必须是新 id
        val second = LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(0, thinkingPartial("second block")),
            0L,
                    )
        assertEquals(LlmStreamEvent.ThinkingStarted(0, "first block"), first)
        assertEquals(LlmStreamEvent.ThinkingStarted(1, "second block"), second)
    }

    @Test
    fun `Thinking with blank text produces no event`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.ThinkingStarted(0, thinkingPartial("")),
                0L,
                            )
        )
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.ThinkingDelta(0, "", thinkingPartial("  ")),
                0L,
                            )
        )
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.ThinkingEnded(0, "", thinkingPartial("")),
                0L,
                            )
        )
    }

    @Test
    fun `TurnAborted with active thinking maps to ThinkingEnded (interrupted counts as done)`() {
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        LlmStreamEventMapper.map(
            TurnEvent.ThinkingStarted(3, thinkingPartial("half thought")),
            0L,
                    )
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnAborted(AssistantMessage(emptyList()), StopCause.UserStop),
            0L,
                    )
        assertEquals(LlmStreamEvent.ThinkingEnded(0, "half thought"), result)
    }

    @Test
    fun `TurnAborted without active thinking stays null`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnAborted(AssistantMessage(emptyList()), StopCause.UserStop),
            0L,
                    )
        assertNull(result)
    }

    // ── 终态映射 ──────────────────────────────────────────────────────────────

    @Test
    fun `TurnCompleted maps to Completed terminal marker`() {
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnCompleted(AssistantMessage(content = listOf(ContentBlock.Text("answer")))),
            0L,
                    )
        assertEquals(LlmStreamEvent.Completed, result)
    }

    @Test
    fun `TurnFailed maps to Error with mapped code`() {
        val error = LLMError(OkiaLLMErrorCode.Transport, "boom")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
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
                    )
        assertEquals(LlmErrorCode.Auth, (result as LlmStreamEvent.Error).code)
    }

    @Test
    fun `TurnFailed maps ContextOverflow to Parse`() {
        val error = LLMError(OkiaLLMErrorCode.ContextOverflow, "context too long")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
                    )
        assertEquals(LlmErrorCode.Parse, (result as LlmStreamEvent.Error).code)
    }

    @Test
    fun `TurnFailed with blank message yields null message`() {
        // 兜底文案归 UI/Service 层，mapper 原文透传不造字符串
        val error = LLMError(OkiaLLMErrorCode.Auth, " ")
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnFailed(AssistantMessage(emptyList()), error),
            0L,
        )
        assertNull((result as LlmStreamEvent.Error).message)
    }

    @Test
    fun `TurnIdleTimeout maps to Error with null message and IdleTimeout code`() {
        // 文案归 UI 层：mapper 只带类型，message 为空
        val result = LlmStreamEventMapper.map(
            TurnEvent.TurnIdleTimeout(AssistantMessage(emptyList())),
            0L,
        )
        val mapped = result as LlmStreamEvent.Error
        assertNull(mapped.message)
        assertEquals(LlmErrorCode.IdleTimeout, mapped.code)
    }

    @Test
    fun `TurnAborted does not produce an error event`() {
        // Mapper 是单例有状态：先重置（真实使用中每个回合由 TurnStarted 触发重置）
        LlmStreamEventMapper.map(TurnEvent.TurnStarted("q"), 0L)
        assertNull(
            LlmStreamEventMapper.map(
                TurnEvent.TurnAborted(AssistantMessage(emptyList()), StopCause.UserStop),
                0L,
                            )
        )
    }
}