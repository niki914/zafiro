package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentStateReducerTest {

    @Test
    fun startRound_setsGeneratingWithNullText() {
        val reduced = AgentStateReducer.startRound()
        assertEquals(AgentState.Generating(text = null), reduced.status)
    }

    @Test
    fun startRound_truncatesAt500Characters() {
        val reduced = AgentStateReducer.reduce(
            AgentStateReducer.startRound(),
            LlmStreamEvent.TextDelta(delta = "a", fullText = "a".repeat(600)),
        )
        val text = (reduced.status as AgentState.Generating).text
        assertEquals(500, text?.length)
        assertEquals("a".repeat(500), text)
    }

    @Test
    fun startRound_safeWithSurrogatePairsAtBoundary() {
        // 499 chars + emoji (high & low surrogate) + tail
        val text = "字".repeat(499) + "😀" + "尾"
        val reduced = AgentStateReducer.reduce(
            AgentStateReducer.startRound(),
            LlmStreamEvent.TextDelta(delta = text, fullText = text),
        )
        assertEquals("字".repeat(499), (reduced.status as AgentState.Generating).text)
    }

    @Test
    fun reduce_textDeltaTracksLatestSegmentText() {
        var state = AgentStateReducer.startRound()
        assertEquals(AgentState.Generating(text = null), state.status)

        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "你好", fullText = "你好，我是助手"),
        )
        assertEquals(AgentState.Generating(text = "你好，我是助手"), state.status)

        // Subsequent TextDelta moves with latest segment text
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "第二句", fullText = "你好，我是助手。第二句"),
        )
        assertEquals(AgentState.Generating(text = "你好，我是助手。第二句"), state.status)
    }

    @Test
    fun reduce_textDeltaAfterToolResetsToNewSegment() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "第一段", fullText = "第一段正文"),
        )
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ToolRunning(ToolCallStatus(name = "bash")),
        )
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "新段", fullText = "新段", isSegmentStart = true),
        )
        assertEquals(AgentState.Generating(text = "新段"), state.status)
    }

    @Test
    fun reduce_toolRunningAndPendingCarriesToolIdentity() {
        var state = AgentStateReducer.startRound()
        assertEquals(AgentState.Generating(text = null), state.status)

        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ToolPending(ToolCallStatus(name = "bash")),
        )
        assertEquals(
            AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = null),
            state.status,
        )

        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ToolRunning(
                ToolCallStatus(name = "bash", label = "bash", argumentsJson = "{\"cmd\":\"ls\"}"),
            ),
        )
        assertEquals(
            AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = "{\"cmd\":\"ls\"}"),
            state.status,
        )

        // ToolSucceeded maintains current phase
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(name = "bash")),
        )
        assertEquals(
            AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = "{\"cmd\":\"ls\"}"),
            state.status,
        )

        // Subsequent text delta brings phase back to Generating
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "结果", fullText = "执行完毕"),
        )
        assertEquals(AgentState.Generating(text = "执行完毕"), state.status)
    }

    @Test
    fun reduce_thinkingStartedCarriesThinkingText() {
        var state = AgentStateReducer.startRound()
        assertEquals(AgentState.Generating(text = null), state.status)

        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ThinkingStarted(id = 1, text = "Let me think"),
        )
        assertEquals(AgentState.Thinking(text = "Let me think"), state.status)

        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ThinkingEnded(id = 1, text = "Done thinking"),
        )
        assertEquals(AgentState.Generating(text = null), state.status)
    }

    @Test
    fun reduce_roundStartedResetsToGenerating() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "第一句回复"),
        )
        state = AgentStateReducer.reduce(state, LlmStreamEvent.RoundStarted)
        assertEquals(AgentState.Generating(text = null), state.status)
    }

    @Test
    fun reduce_completedCarriesLastText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "第一句回复"),
        )

        state = AgentStateReducer.reduce(state, LlmStreamEvent.Completed)
        assertEquals(AgentState.Idle(TurnOutcome.Completed, lastText = "第一句回复"), state.status)
    }

    @Test
    fun reduce_completedWithoutSpeechHasNullLastText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.ToolRunning(ToolCallStatus(name = "bash")),
        )

        state = AgentStateReducer.reduce(state, LlmStreamEvent.Completed)
        assertEquals(AgentState.Idle(TurnOutcome.Completed, lastText = null), state.status)
    }

    @Test
    fun reduce_errorCarriesLastText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "正文"),
        )

        state = AgentStateReducer.reduce(state, LlmStreamEvent.Error(message = "Network error"))
        assertEquals(AgentState.Idle(TurnOutcome.Failed, lastText = "正文"), state.status)
    }

    @Test
    fun interrupt_carriesLastText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "说到一半"),
        )
        val interrupted = AgentStateReducer.interrupt(state)

        assertEquals(AgentState.Idle(TurnOutcome.Interrupted, lastText = "说到一半"), interrupted.status)
    }

    @Test
    fun interrupt_withoutSpeechHasNullLastText() {
        val interrupted = AgentStateReducer.interrupt(AgentStateReducer.startRound())

        assertEquals(AgentState.Idle(TurnOutcome.Interrupted, lastText = null), interrupted.status)
    }

    @Test
    fun stopping_setsStoppingWithoutText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "正", fullText = "正文"),
        )
        val stopping = AgentStateReducer.stopping(state)

        assertEquals(AgentState.Stopping, stopping.status)
    }

    @Test
    fun reduce_whenStopping_ignoresTrailingDeltasAndTools() {
        val state = AgentStateReducer.stopping(AgentStateReducer.startRound())

        val deltaState = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "字", fullText = "文字"),
        )
        assertEquals(AgentState.Stopping, deltaState.status)

        val toolState = AgentStateReducer.reduce(
            deltaState,
            LlmStreamEvent.ToolRunning(ToolCallStatus(name = "bash")),
        )
        assertEquals(AgentState.Stopping, toolState.status)
    }

    @Test
    fun reduce_whenStopping_completedCarriesLastText() {
        var state = AgentStateReducer.startRound()
        state = AgentStateReducer.reduce(
            state,
            LlmStreamEvent.TextDelta(delta = "一", fullText = "停止前的话"),
        )
        state = AgentStateReducer.stopping(state)

        val completed = AgentStateReducer.reduce(state, LlmStreamEvent.Completed)
        assertEquals(AgentState.Idle(TurnOutcome.Completed, lastText = "停止前的话"), completed.status)

        val failed = AgentStateReducer.reduce(state, LlmStreamEvent.Error(message = "err"))
        assertEquals(AgentState.Idle(TurnOutcome.Failed, lastText = "停止前的话"), failed.status)
    }

    @Test
    fun reset_returnsFreshIdle() {
        val reset = AgentStateReducer.reset()
        assertEquals(AgentState.Idle(lastOutcome = null), reset.status)
        assertNull((reset.status as AgentState.Idle).lastOutcome)
    }
}
