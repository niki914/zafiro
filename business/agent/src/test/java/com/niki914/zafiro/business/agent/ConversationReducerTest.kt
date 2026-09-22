package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归约器的纯单测：不碰引擎、不碰 Room。
 * 覆盖 id 规则（M3a）与折叠结果（M3b）。
 */
class ConversationReducerTest {

    @Test
    fun turnIdAndBlockIdArePositionalAndPure() {
        assertEquals("t0", turnIdAt(0).value)
        assertEquals("t7", turnIdAt(7).value)
        assertEquals("t2:3", blockIdAt(2, 3))

        // 同一输入恒得同一值（两条装配路径共用的前提）
        assertEquals(turnIdAt(3), turnIdAt(3))
        assertEquals(blockIdAt(1, 2), blockIdAt(1, 2))
    }

    @Test
    fun startTurn_assignsPositionalTurnId() {
        val first = ConversationReducer.startTurn(Conversation(), "你好", emptyList())
        val second = ConversationReducer.startTurn(first.conversation, "再来", emptyList())

        assertEquals(listOf("t0", "t1"), second.conversation.turns.map { it.id.value })
        assertEquals("你好", second.conversation.turns.first().userText)
        assertEquals(emptyMap<Int, Int>(), second.thinkingSlots)
    }

    @Test
    fun startTurn_dropsFailureAndRetryingBlocksOfPreviousTurns() {
        val started = startTurn("q1")
        val withFailure = reduce(
            started,
            LlmStreamEvent.Error(message = "boom", code = LlmErrorCode.Transport),
        )
        val withRetry = reduce(withFailure, retrying(attempt = 1))
        assertTrue(withRetry.conversation.turns.last().blocks.any { it is TurnBlock.Failure })

        val next = ConversationReducer.startTurn(withRetry.conversation, "q2", emptyList())
        val blocks = next.conversation.turns.first().blocks
        assertTrue(blocks.none { it is TurnBlock.Failure || it is TurnBlock.Retrying })
    }

    @Test
    fun textDelta_mergesIntoSameBlockAndNewSegmentStartsANewBlock() {
        val afterText = reduce(startTurn("q"), text("你"), text("好"))
        assertEquals(
            listOf(TurnBlock.Text(id = "t0:0", text = "你好")),
            afterText.conversation.turns.last().blocks,
        )

        // 工具块之后的新文本段：新块、新位置 id，旧块不动
        val withTool = reduce(
            afterText,
            LlmStreamEvent.ToolRunning(call = call(callId = "c1", name = "search")),
        )
        val afterSecondSegment = reduce(withTool, text("世界"))
        assertEquals(
            listOf("t0:0", "t0:1", "t0:2"),
            afterSecondSegment.conversation.turns.last().blocks.map { it.id },
        )
        assertEquals(
            "世界",
            (afterSecondSegment.conversation.turns.last().blocks.last() as TurnBlock.Text).text,
        )
    }

    @Test
    fun thinking_echoAndEndedUpdateTheSameBlock() {
        val turnIndex = 0
        val state = reduce(
            startTurn("q"),
            LlmStreamEvent.ThinkingStarted(id = 0, text = "想"),
        )
        val echoed = reduce(state, LlmStreamEvent.ThinkingStarted(id = 0, text = "想一下"))
        val ended = reduce(echoed, LlmStreamEvent.ThinkingEnded(id = 0, text = "想完了"))

        val blocks = ended.conversation.turns.last().blocks
        assertEquals(1, blocks.size)
        val thinking = blocks.single() as TurnBlock.Thinking
        assertEquals(blockIdAt(turnIndex, 0), thinking.id)
        assertEquals("想完了", thinking.text)
        assertTrue(thinking.isComplete)
    }

    @Test
    fun retryReusesTheThinkingSlotOfTheFailedAttempt() {
        // mapper 在回合重试时把思考 id 归零（同一 id 再次出现）
        val firstAttempt = reduce(startTurn("q"), LlmStreamEvent.ThinkingStarted(id = 0, text = "第一次"))
        val failed = reduce(firstAttempt, LlmStreamEvent.Error(message = null, code = LlmErrorCode.Transport))
        val secondAttempt = reduce(failed, LlmStreamEvent.ThinkingStarted(id = 0, text = "第二次"))

        val thinking = secondAttempt.conversation.turns.last().blocks.filterIsInstance<TurnBlock.Thinking>()
        assertEquals(1, thinking.size)
        assertEquals("第二次", thinking.single().text)
    }

    @Test
    fun toolPlaceholderIsUpgradedInPlaceWhenCallIdArrives() {
        val pending = reduce(
            startTurn("q"),
            LlmStreamEvent.ToolPending(call = call(callId = null, name = "search")),
        )
        val blockId = pending.conversation.turns.last().blocks.single().id

        val running = reduce(
            pending,
            LlmStreamEvent.ToolRunning(call = call(callId = "c1", name = "search", arguments = "{}")),
        )
        val tool = running.conversation.turns.last().blocks.single() as TurnBlock.Tool
        assertEquals(blockId, tool.id)
        assertEquals("c1", tool.invocation.id)
        assertEquals("{}", tool.invocation.argumentsJson)
        assertEquals(null, tool.outcome)
    }

    @Test
    fun toolOutcomeIsSettledOnTheSameBlock() {
        val running = reduce(
            startTurn("q"),
            LlmStreamEvent.ToolRunning(call = call(callId = "c1", name = "search")),
        )
        val succeeded = reduce(
            running,
            LlmStreamEvent.ToolSucceeded(call = call(callId = "c1", name = "search"), outputText = "ok"),
        )
        val tool = succeeded.conversation.turns.last().blocks.single() as TurnBlock.Tool
        assertEquals(ToolOutcome.Succeeded(resultText = "ok"), tool.outcome)
    }

    @Test
    fun retryingCardIsReplacedNotStacked() {
        val state = reduce(startTurn("q"), retrying(attempt = 1))
        val replaced = reduce(state, retrying(attempt = 2))

        val retrying = replaced.conversation.turns.last().blocks.filterIsInstance<TurnBlock.Retrying>()
        assertEquals(1, retrying.size)
        assertEquals(2, retrying.single().attempt)
    }

    @Test
    fun errorAppendsFailureWithMappedCode() {
        val state = reduce(
            startTurn("q"),
            LlmStreamEvent.Error(message = "bad", code = LlmErrorCode.RetryExhausted, attempts = 3),
        )
        val failure = state.conversation.turns.last().blocks.single() as TurnBlock.Failure
        assertEquals("bad", failure.message)
        assertEquals(TurnFailureCode.RetryExhausted, failure.code)
        assertEquals(3, failure.attempts)
    }

    @Test
    fun interruptMarksUnsettledToolsFailed() {
        val running = reduce(
            startTurn("q"),
            LlmStreamEvent.ToolRunning(call = call(callId = "c1", name = "search")),
        )
        val interrupted = ConversationReducer.interrupt(running.conversation)
        val tool = interrupted.turns.last().blocks.single() as TurnBlock.Tool
        assertEquals(ToolOutcome.Failed(message = FAILED_REASON_INTERRUPTED), tool.outcome)
    }

    @Test
    fun reduceWithoutTurnIsNoOp() {
        val state = Reduced(Conversation())
        assertEquals(state, ConversationReducer.reduce(state, text("delta")))
    }

    @Test
    fun idsDifferBetweenTurnsAndBlocksStayStableAcrossFileDeltas() {
        val first = reduce(startTurn("q0"), text("a"))
        val second = ConversationReducer.startTurn(first.conversation, "q1", emptyList())
        val secondWithText = reduce(second, text("b"))

        val ids = secondWithText.conversation.turns.map { it.id.value }
        assertEquals(listOf("t0", "t1"), ids)
        assertNotEquals(
            secondWithText.conversation.turns[0].blocks.single().id,
            secondWithText.conversation.turns[1].blocks.single().id,
        )
    }

    // ── 构造helper ──────────────────────────────────────────────────────────

    private fun startTurn(query: String): Reduced =
        ConversationReducer.startTurn(
            conversation = Conversation(),
            userText = query,
            attachments = listOf(Attachment(path = "/tmp/a.jpg")),
        )

    private fun reduce(state: Reduced, vararg events: LlmStreamEvent): Reduced =
        events.fold(state) { acc, event -> ConversationReducer.reduce(acc, event) }

    private fun text(delta: String): LlmStreamEvent.TextDelta =
        LlmStreamEvent.TextDelta(delta = delta, fullText = delta)

    private fun retrying(attempt: Int): LlmStreamEvent.Retrying =
        LlmStreamEvent.Retrying(attempt = attempt, maxAttempts = 3, delayMs = 100L, reason = "timeout")

    private fun call(callId: String?, name: String, arguments: String? = null): ToolCallStatus =
        ToolCallStatus(callId = callId, name = name, label = name, argumentsJson = arguments)
}
