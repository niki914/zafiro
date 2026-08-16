package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.EmptyToolRegistry
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 分层重试测试（G4/G5/G6 裁决）：
 * 传输层（config.retryPolicy）= 发送阶段（stream 抛错 / 非 2xx），Retry-After
 * 优先；回合层（turnRetryPolicy）= 段首重试（发送阶段耗尽 / 流中断），嵌套
 * 对齐 pi；段首重试丢弃 partial、复用已提交历史；重试可中断。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealAgentLoopRetryTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun textOf(message: AssistantMessage): String =
        (message.content.single() as ContentBlock.Text).text

    private fun completed(stopReason: StopReason? = StopReason.Stop) =
        ProtocolEvent.Completed(stopReason = stopReason)

    private fun loopRequest(
        events: Flow<ProtocolEvent>,
        engine: FakeHttpEngine = FakeHttpEngine(),
        onCommit: suspend (List<Message>) -> Unit = {}
    ): LoopRequest = LoopRequest(
        snapshot = RequestSnapshot(
            endpoint = "https://api.test/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = null,
            temperature = 0.7f,
            maxTokens = 100,
            headers = emptyMap(),
            timeouts = HttpTimeouts(1_000, 1_000, 1_000),
            tools = emptyList()
        ),
        history = listOf(user("hi")),
        input = "hi",
        options = LoopOptions(),
        idleTimeoutSeconds = null,
        toolRegistry = EmptyToolRegistry(),
        protocolMapper = FakeProtocolMapper(events),
        hooks = emptyList(),
        httpEngine = engine,
        retryPolicy = RetryPolicy(),
        onCommit = onCommit
    )

    private fun loopRequest(
        events: List<ProtocolEvent>,
        engine: FakeHttpEngine = FakeHttpEngine(),
        onCommit: suspend (List<Message>) -> Unit = {}
    ): LoopRequest = loopRequest(events.asFlow(), engine = engine, onCommit = onCommit)

    private fun errorResponse(status: Int, body: String = "boom", headers: Map<String, String> = emptyMap()) =
        StreamResponse.Error(status, headers, body)

    private fun okResponse() =
        StreamResponse.Ok(200, mapOf("Content-Type" to "text/event-stream"), emptyFlow())

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    // hook 调用记录（A10：重试时每轮请求 hooks 重跑）
    private class RecordingHooks(private val calls: MutableList<String>) : Hooks {
        override suspend fun beforeInput(input: com.niki914.okia.hooks.InputHolder) { calls += "beforeInput" }
        override suspend fun afterInput(input: com.niki914.okia.hooks.InputHolder) { calls += "afterInput" }
        override suspend fun beforeSerialization(request: SerializationHolder) { calls += "beforeSerialization" }
        override suspend fun afterSerialization(request: SerializationHolder, httpRequest: HttpRequest) {
            calls += "afterSerialization"
        }
        override suspend fun beforeRequest(request: HttpRequestHolder) { calls += "beforeRequest" }
        override suspend fun afterRequest(request: HttpRequest) { calls += "afterRequest" }
    }

    // ── A. 传输层重试（发送阶段） ──────────────────────────────────────────

    @Test
    fun transient503sRetryThenSucceed() = runTest {
        val engine = FakeHttpEngine()
        var call = 0
        engine.streamResult = { if (call++ < 2) errorResponse(503) else okResponse() }
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        // 1 次初始 + 2 次重试 = 3 个请求
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        assertEquals(3, engine.streamedRequests.size)
        val retries = emitted.filterIsInstance<TurnEvent.RetryScheduled>()
        assertEquals(2, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals(2, retries[1].attempt)
        assertEquals(3, retries[0].maxAttempts)
        assertEquals("HTTP 503", retries[0].reason)
        // 指数退避：attempt1 = base(500) ± 10%，attempt2 = 2*base ± 10%
        assertTrue(retries[0].delayMs in 450..550)
        assertTrue(retries[1].delayMs in 900..1100)
    }

    @Test
    fun transportRetryExhaustedWithoutTurnPolicyReturnsOriginalCode() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        val failed = result as TurnResult.Failed
        // 无回合层配置：如实返回可重试原错误（库不自动升级）
        assertEquals(LLMErrorCode.Overloaded, failed.error.code)
        assertEquals(503, failed.error.statusCode)
        // maxAttempts = 重试次数（初始请求不计数）：1 初始 + 3 重试 = 4 请求
        assertEquals(4, engine.streamedRequests.size)
        assertEquals(3, emitted.count { it is TurnEvent.RetryScheduled })
    }

    @Test
    fun retryAfterHeaderTakesPriorityOverBackoff() = runTest {
        val engine = FakeHttpEngine()
        var call = 0
        engine.streamResult = {
            if (call++ == 0) errorResponse(429, headers = mapOf("Retry-After" to "10")) else okResponse()
        }
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        val retry = emitted.filterIsInstance<TurnEvent.RetryScheduled>().single()
        // Retry-After: 10s 优先于指数退避（500ms）
        assertEquals(10_000, retry.delayMs)
    }

    @Test
    fun retryAfterMsHeaderTakesTopPriority() = runTest {
        val engine = FakeHttpEngine()
        var call = 0
        engine.streamResult = {
            if (call++ == 0) {
                errorResponse(429, headers = mapOf("retry-after-ms" to "250", "Retry-After" to "10"))
            } else okResponse()
        }
        val emitted = mutableListOf<TurnEvent>()
        runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        val retry = emitted.filterIsInstance<TurnEvent.RetryScheduled>().single()
        assertEquals(250, retry.delayMs)
    }

    @Test
    fun exponentialBackoffSequenceWithCap() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        val request = loopRequest(listOf(completed()), engine = engine).copy(
            retryPolicy = RetryPolicy(maxAttempts = 4, baseDelayMs = 100, maxDelayMs = 1_000)
        )
        val emitted = mutableListOf<TurnEvent>()
        runLoop(request, emitted)

        val delays = emitted.filterIsInstance<TurnEvent.RetryScheduled>().map { it.delayMs }
        assertEquals(4, delays.size)
        // base=100：100, 200, 400, 800（未触上限 1000），各 ±10%
        assertTrue(delays[0] in 90..110)
        assertTrue(delays[1] in 180..220)
        assertTrue(delays[2] in 360..440)
        assertTrue(delays[3] in 720..880)
    }

    @Test
    fun exponentialBackoffCapsAtMaxDelay() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        val request = loopRequest(listOf(completed()), engine = engine).copy(
            retryPolicy = RetryPolicy(maxAttempts = 6, baseDelayMs = 1_000, maxDelayMs = 1_500)
        )
        val emitted = mutableListOf<TurnEvent>()
        runLoop(request, emitted)

        val delays = emitted.filterIsInstance<TurnEvent.RetryScheduled>().map { it.delayMs }
        assertEquals(6, delays.size)
        // 1000, 1500(上限), 1500(上限), ... 各 ±10%
        assertTrue(delays[0] in 900..1100)
        assertTrue(delays[1] in 1350..1650)
        assertTrue(delays[2] in 1350..1650)
    }

    @Test
    fun auth401FailsImmediatelyWithoutRetry() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(401) } }
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        assertEquals(LLMErrorCode.Auth, (result as TurnResult.Failed).error.code)
        assertEquals(1, engine.streamedRequests.size)
        assertTrue(emitted.none { it is TurnEvent.RetryScheduled })
    }

    @Test
    fun clientError400FailsImmediatelyAsParse() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(400) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))

        // G4：400 系归 Parse（不可重试），修正旧实现把 400 归 Transport 白等
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
        assertEquals(1, engine.streamedRequests.size)
    }

    @Test
    fun quota402FailsImmediately() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(402) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Quota, (result as TurnResult.Failed).error.code)
        assertEquals(1, engine.streamedRequests.size)
    }

    @Test
    fun networkErrorRetriesThenFailsWithTransport() = runTest {
        val engine = FakeHttpEngine().apply { streamError = RuntimeException("connection refused") }
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed()), engine = engine), emitted)

        assertEquals(LLMErrorCode.Transport, (result as TurnResult.Failed).error.code)
        // maxAttempts = 重试次数：1 初始 + 3 重试 = 4 请求
        assertEquals(4, engine.streamedRequests.size)
        assertEquals(3, emitted.count { it is TurnEvent.RetryScheduled })
    }

    @Test
    fun cancellationDuringRetryBackoffPropagates() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        var caught: CancellationException? = null
        val job = launch {
            try {
                RealAgentLoop().run(loopRequest(listOf(completed()), engine = engine)) { }
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent() // 第一次 503 → RetryScheduled → 退避 delay 挂起
        assertTrue(caught == null)
        job.cancel()
        runCurrent()
        assertTrue(caught != null)
    }

    @Test
    fun hooksRunOnEveryRetryAttempt() = runTest {
        val calls = mutableListOf<String>()
        val engine = FakeHttpEngine()
        var call = 0
        engine.streamResult = { if (call++ < 2) errorResponse(503) else okResponse() }
        val request = loopRequest(listOf(completed()), engine = engine).copy(hooks = listOf(RecordingHooks(calls)))

        val result = runLoop(request)
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // 初始 + 2 次重试 = 3 次发送尝试：Request 时机每尝试重跑（对齐 pi fresh request）
        assertEquals(3, calls.count { it == "beforeRequest" })
        assertEquals(3, calls.count { it == "afterRequest" })
        // Serialization + buildRequest 每段一次（重试重发同一请求体，不重复构建）
        assertEquals(1, calls.count { it == "beforeSerialization" })
        assertEquals(1, calls.count { it == "afterSerialization" })
        // Input 时机只在回合入口一次
        assertEquals(1, calls.count { it == "beforeInput" })
    }

    // ── B. 回合层段首重试（G5：丢弃 partial、复用历史；G6：嵌套） ────────

    @Test
    fun streamInterruptionDropsPartialAndRetriesSegment() = runTest {
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(ProtocolEvent.TextDelta("par")), // round0：发 1 事件后中断
                listOf(ProtocolEvent.TextDelta("ok"), completed()) // round1：重试成功
            )
        ).apply {
            interruptRound = 0
            interruptAfterEvents = 1
        }
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val request = loopRequest(emptyFlow(), onCommit = { commits += it }).copy(
            protocolMapper = mapper,
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 1))
        )
        val result = runLoop(request, emitted)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // partial "par" 未 commit（段首重试丢弃）；只有重试轮的 "ok"
        assertEquals(1, commits.size)
        assertEquals("ok", textOf((commits.single().single() as Message.Assistant).message))
        val retry = emitted.filterIsInstance<TurnEvent.RetryScheduled>().single()
        assertEquals(1, retry.maxAttempts)
        assertTrue(retry.reason.contains("stream"))
    }

    @Test
    fun streamInterruptionWithoutTurnPolicyCommitsPartialAndFails() = runTest {
        val mapper = FakeProtocolMapper(
            listOf(listOf(ProtocolEvent.TextDelta("par")))
        ).apply {
            interruptRound = 0
            interruptAfterEvents = 1
        }
        val commits = mutableListOf<List<Message>>()
        val request = loopRequest(emptyFlow(), onCommit = { commits += it }).copy(protocolMapper = mapper)

        val result = runLoop(request)
        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.Transport, failed.error.code)
        // 最终失败：partial 保留（fail 的 commitPartial 路径）
        assertEquals(1, commits.size)
        assertEquals("par", textOf((commits.single().single() as Message.Assistant).message))
    }

    @Test
    fun segmentRetryReusesCommittedToolResults() = runTest {
        // 用户核心场景（G5）：两轮工具调用已 commit，第三轮 assistant 生成中
        // 网络中断 → 段首重试的请求历史以最后一条 ToolResult 结尾，结果全复用
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor()
        registry.register(localTool("t1"), executor)
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(ProtocolEvent.ToolCallReady("c1", "t1", "{}"), completed(StopReason.ToolUse)),
                listOf(ProtocolEvent.ToolCallReady("c2", "t1", "{}"), completed(StopReason.ToolUse)),
                listOf(ProtocolEvent.TextDelta("half")), // round2 中断
                listOf(ProtocolEvent.TextDelta("final"), completed()) // round3 重试成功
            )
        ).apply {
            interruptRound = 2
            interruptAfterEvents = 1
        }
        val request = loopRequest(emptyFlow()).copy(
            protocolMapper = mapper,
            toolRegistry = registry,
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 1))
        )
        val result = runLoop(request)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // 最后一次 buildRequest 的历史以 ToolResult 结尾（最新合法状态，模型"装作无事发生"）
        val lastHistory = mapper.builtHistories.last()
        assertTrue(lastHistory.last() is Message.ToolResult)
        // 两轮工具结果全部复用
        assertEquals(2, lastHistory.count { it is Message.ToolResult })
        // 工具只执行 2 次：重试不重跑已完成的工具轮、不重发历史轮次
        assertEquals(2, executor.calls.size)
        // 中断轮的 partial "half" 未进任何请求历史（只存在 content 文本中）
        assertTrue(
            mapper.builtHistories.none { h ->
                h.any { msg ->
                    msg is Message.Assistant && msg.message.content.any {
                        (it as? ContentBlock.Text)?.text?.contains("half") == true
                    }
                }
            }
        )
    }

    @Test
    fun nestedRetryEscalatesTransportExhaustionToTurnRetry() = runTest {
        val engine = FakeHttpEngine()
        var call = 0
        // 段1 传输层耗尽：初始 503 + 1 次重试 503；段2 成功
        engine.streamResult = { if (call++ < 2) errorResponse(503) else okResponse() }
        val emitted = mutableListOf<TurnEvent>()
        val request = loopRequest(listOf(completed()), engine = engine).copy(
            retryPolicy = RetryPolicy(maxAttempts = 1), // 传输层 1 次
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 1)) // 回合层 1 次
        )
        val result = runLoop(request, emitted)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // 传输层 1 次重试 + 回合层 1 次段首重试（嵌套对齐 pi）
        val retries = emitted.filterIsInstance<TurnEvent.RetryScheduled>()
        assertEquals(2, retries.size)
        assertEquals("HTTP 503", retries[0].reason)
        assertTrue(retries[1].reason.contains("segment"))
        // 段1：2 个请求（初始+重试）；段2：1 个请求（成功）
        assertEquals(3, engine.streamedRequests.size)
    }

    @Test
    fun turnRetryExhaustedFailsWithRetryExhausted() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        val emitted = mutableListOf<TurnEvent>()
        val request = loopRequest(listOf(completed()), engine = engine).copy(
            retryPolicy = RetryPolicy(maxAttempts = 1),
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 2))
        )
        val result = runLoop(request, emitted)

        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.RetryExhausted, failed.error.code)
        // 传输层每段 1 次 × 3 段 + 回合层 2 次 = 5 次 RetryScheduled
        assertEquals(5, emitted.count { it is TurnEvent.RetryScheduled })
        assertEquals(6, engine.streamedRequests.size)
    }

    @Test
    fun toolFailureDoesNotTriggerTurnRetry() = runTest {
        // 回归确认：工具失败路径 = 结果回喂模型（T6 契约），回合层重试不介入
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor().apply {
            outcome = ToolCallOutcome.Failure("tool exploded")
        }
        registry.register(localTool("t1"), executor)
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(ProtocolEvent.ToolCallReady("c1", "t1", "{}"), completed(StopReason.ToolUse)),
                listOf(ProtocolEvent.TextDelta("after"), completed())
            )
        )
        val request = loopRequest(emptyFlow()).copy(
            protocolMapper = mapper,
            toolRegistry = registry,
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 3))
        )
        val result = runLoop(request)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        assertEquals(1, executor.calls.size) // 工具只执行一次，不重试
        assertEquals(2, mapper.parseStreamCalls) // 两轮正常
    }

    @Test
    fun segmentRetryIsCancellable() = runTest {
        // 段首重试的退避 delay 可被取消（重试可中断）
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(ProtocolEvent.TextDelta("par")),
                listOf(ProtocolEvent.TextDelta("ok"), completed())
            )
        ).apply {
            interruptRound = 0
            interruptAfterEvents = 1
        }
        var caught: CancellationException? = null
        val job = launch {
            try {
                RealAgentLoop().run(
                    loopRequest(emptyFlow()).copy(
                        protocolMapper = mapper,
                        options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 3))
                    )
                ) { }
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent() // 中断 → 段首重试退避 delay 挂起
        assertTrue(caught == null)
        job.cancel()
        runCurrent()
        assertTrue(caught != null)
    }
}
