package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentStatusReducerTest {

    @Test
    fun startRound_setsGeneratingAndPreview() {
        val reduced = AgentStatusReducer.startRound("帮我查天气")
        assertEquals(AgentPhase.Generating, reduced.status.phase)
        assertNull(reduced.status.outcome)
        assertEquals("帮我查天气", reduced.status.preview)
    }

    @Test
    fun startRound_normalizesWhitespace() {
        val reduced = AgentStatusReducer.startRound("  第一行\n\t第二行   ")
        assertEquals("第一行 第二行", reduced.status.preview)
    }

    @Test
    fun startRound_blankQueryProducesNullPreview() {
        val reduced = AgentStatusReducer.startRound("   \n ")
        assertNull(reduced.status.preview)
    }

    @Test
    fun startRound_truncatesAt120Characters() {
        val longQuery = "a".repeat(150)
        val reduced = AgentStatusReducer.startRound(longQuery)
        assertEquals(120, reduced.status.preview?.length)
        assertEquals("a".repeat(120), reduced.status.preview)
    }

    @Test
    fun startRound_safeWithSurrogatePairsAtBoundary() {
        // 119 chars + emoji (high & low surrogate) + tail
        val text = "字".repeat(119) + "😀" + "尾"
        val reduced = AgentStatusReducer.startRound(text)
        assertEquals("字".repeat(119), reduced.status.preview)
    }

    @Test
    fun reduce_textDeltaUpdatesPreviewToFirstAgentText() {
        var state = AgentStatusReducer.startRound("问题")
        assertEquals("问题", state.status.preview)

        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "你好", fullText = "你好，我是助手"),
        )
        assertEquals(AgentPhase.Generating, state.status.phase)
        assertEquals("你好，我是助手", state.status.preview)

        // Subsequent TextDelta keeps first agent text as the in-progress preview
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "第二句", fullText = "你好，我是助手。第二句"),
        )
        assertEquals("你好，我是助手", state.status.preview)
    }

    @Test
    fun reduce_toolRunningAndPendingSwitchesPhase() {
        var state = AgentStatusReducer.startRound("执行脚本")
        assertEquals(AgentPhase.Generating, state.status.phase)

        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.ToolPending(ToolCallStatus(name = "bash")),
        )
        assertEquals(AgentPhase.ToolRunning, state.status.phase)

        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.ToolRunning(ToolCallStatus(name = "bash")),
        )
        assertEquals(AgentPhase.ToolRunning, state.status.phase)

        // ToolSucceeded maintains current phase
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(name = "bash")),
        )
        assertEquals(AgentPhase.ToolRunning, state.status.phase)

        // Subsequent text delta brings phase back to Generating
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "结果", fullText = "执行完毕"),
        )
        assertEquals(AgentPhase.Generating, state.status.phase)
    }

    @Test
    fun reduce_completedSetsIdleWithCompletedOutcomeAndLatestPreview() {
        var state = AgentStatusReducer.startRound("问")
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "第一句回复"),
        )
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "二", fullText = "第一句回复。最终回复总结"),
        )

        state = AgentStatusReducer.reduce(state, LlmStreamEvent.Completed)
        assertEquals(AgentPhase.Idle, state.status.phase)
        assertEquals(TurnOutcome.Completed, state.status.outcome)
        assertEquals("第一句回复。最终回复总结", state.status.preview)
    }

    @Test
    fun reduce_errorSetsIdleWithFailedOutcomeAndNullPreview() {
        var state = AgentStatusReducer.startRound("问")
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "正文"),
        )

        state = AgentStatusReducer.reduce(state, LlmStreamEvent.Error(message = "Network error"))
        assertEquals(AgentPhase.Idle, state.status.phase)
        assertEquals(TurnOutcome.Failed, state.status.outcome)
        assertNull(state.status.preview)
    }

    @Test
    fun interrupt_setsIdleWithInterruptedOutcomeAndNullPreview() {
        val state = AgentStatusReducer.startRound("问")
        val interrupted = AgentStatusReducer.interrupt(state)

        assertEquals(AgentPhase.Idle, interrupted.status.phase)
        assertEquals(TurnOutcome.Interrupted, interrupted.status.outcome)
        assertNull(interrupted.status.preview)
    }

    @Test
    fun stopping_setsStoppingPhaseAndRetainsPreview() {
        var state = AgentStatusReducer.startRound("问")
        state = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "正", fullText = "正文"),
        )
        val stopping = AgentStatusReducer.stopping(state)

        assertEquals(AgentPhase.Stopping, stopping.status.phase)
        assertNull(stopping.status.outcome)
        assertEquals("正文", stopping.status.preview)
    }

    @Test
    fun reduce_whenStopping_ignoresTrailingDeltasAndTools() {
        val state = AgentStatusReducer.stopping(AgentStatusReducer.startRound("问"))

        val deltaState = AgentStatusReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "字", fullText = "文字"),
        )
        assertEquals(AgentPhase.Stopping, deltaState.status.phase)

        val toolState = AgentStatusReducer.reduce(
            deltaState,
            LlmStreamEvent.ToolRunning(ToolCallStatus(name = "bash")),
        )
        assertEquals(AgentPhase.Stopping, toolState.status.phase)
    }

    @Test
    fun reduce_whenStopping_allowsCompletedOrError() {
        val state = AgentStatusReducer.stopping(AgentStatusReducer.startRound("问"))

        val completed = AgentStatusReducer.reduce(state, LlmStreamEvent.Completed)
        assertEquals(AgentPhase.Idle, completed.status.phase)
        assertEquals(TurnOutcome.Completed, completed.status.outcome)

        val failed = AgentStatusReducer.reduce(state, LlmStreamEvent.Error(message = "err"))
        assertEquals(AgentPhase.Idle, failed.status.phase)
        assertEquals(TurnOutcome.Failed, failed.status.outcome)
    }

    @Test
    fun reset_returnsDefaultStatus() {
        val reset = AgentStatusReducer.reset()
        assertEquals(AgentPhase.Idle, reset.status.phase)
        assertNull(reset.status.outcome)
        assertNull(reset.status.preview)
    }
}
