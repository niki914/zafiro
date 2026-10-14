// 保护：复读熔断的端到端行为——命中即中止本段、不可重试、partial 已 commit。
// 若哨兵 catch 顺序被改动（落到泛 Exception 兜底），复读会被误判为可重试并重发，
// 这是用户可见的退化（复读内容重流一遍）。
package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealAgentLoopRepetitionTest {

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun completed(stopReason: StopReason? = StopReason.Stop) =
        ProtocolEvent.Completed(stopReason = stopReason)

    private fun loopRequest(
        events: List<ProtocolEvent>,
        engine: FakeHttpEngine = FakeHttpEngine(),
        options: LoopOptions = LoopOptions(),
        onCommit: suspend (List<Message>) -> Unit = {}
    ): LoopRequest = LoopRequest(
        snapshot = RequestSnapshot(
            endpoint = "https://api.test/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = null,
            temperature = 1.0f,
            maxTokens = 100,
            headers = emptyMap(),
            timeouts = HttpTimeouts(1_000, 1_000, 1_000),
            tools = emptyList()
        ),
        history = listOf(user("hi")),
        input = "hi",
        options = options,
        idleTimeoutSeconds = null,
        toolRegistry = DefaultToolRegistry(),
        protocolMapper = FakeProtocolMapper(events.asFlow()),
        hooks = emptyList(),
        httpEngine = engine,
        retryPolicy = RetryPolicy(),
        onCommit = onCommit
    )

    @Test
    fun repeatedTextAbortsTurnWithRepetitionError() = runTest {
        val emitted = mutableListOf<TurnEvent>()
        val events = listOf(
            ProtocolEvent.TextDelta("abcd".repeat(10)),
            completed()
        )

        val result = RealAgentLoop().run(loopRequest(events)) { emitted += it }

        assertTrue(result is TurnResult.Failed)
        assertEquals(LLMErrorCode.Repetition, (result as TurnResult.Failed).error.code)

        val failed = emitted.filterIsInstance<TurnEvent.TurnFailed>().single()
        assertEquals(LLMErrorCode.Repetition, failed.error.code)
    }

    @Test
    fun repeatedThinkingAbortsTurn() = runTest {
        val events = listOf(
            ProtocolEvent.ThinkingDelta("let me ".repeat(10)),
            completed()
        )

        val result = RealAgentLoop().run(loopRequest(events)) {}

        assertTrue(result is TurnResult.Failed)
        assertEquals(LLMErrorCode.Repetition, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun partialOutputIsCommittedOnAbort() = runTest {
        val commits = mutableListOf<List<Message>>()
        val events = listOf(
            ProtocolEvent.TextDelta("abcd".repeat(10)),
            completed()
        )

        RealAgentLoop().run(loopRequest(events, onCommit = { commits += it })) {}

        // 命中时的部分产出也进历史（与 idle/取消路径的 commitPartial 契约一致）
        assertTrue(commits.isNotEmpty())
    }

    @Test
    fun repetitionIsNotRetried() = runTest {
        val engine = FakeHttpEngine()
        val events = listOf(
            ProtocolEvent.TextDelta("abcd".repeat(10)),
            completed()
        )
        // 配置了回合层重试预算：复读仍不得重发（不可重试分类）
        val options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 3))

        val result = RealAgentLoop().run(
            loopRequest(events, engine = engine, options = options)
        ) {}

        assertTrue(result is TurnResult.Failed)
        assertEquals(1, engine.streamedRequests.size)
    }

    @Test
    fun normalTextCompletesWithoutAbort() = runTest {
        val events = listOf(
            ProtocolEvent.TextDelta("This is a perfectly normal回答 with no repetition."),
            completed()
        )

        val result = RealAgentLoop().run(loopRequest(events)) {}

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }
}
