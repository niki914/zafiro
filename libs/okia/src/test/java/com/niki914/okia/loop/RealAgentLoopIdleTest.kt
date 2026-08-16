package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.DeepSeekChatCompletionProtocol
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.EmptyToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 idle 检测测试（G7 裁决）：idle = agent 活跃度（ProtocolEvent 到达），
 * keep-alive（SseLine 层，不产出 ProtocolEvent）不重置；计时只在流收集段
 * 活，工具执行段不计；超时 = 独立终态（IdleTimeout），partial 消息 commit
 * 进历史（超时也写入），不重试、不触发 beforeStop。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealAgentLoopIdleTest {

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

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    // ── D. idle（agent 活跃度） ───────────────────────────────────────────

    @Test
    fun activeStreamWithRegularDeltasDoesNotTimeOut() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val request = loopRequest(events).copy(idleTimeoutSeconds = 1)
        val result = async { runLoop(request) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        // 连续活跃：delta 间隔 500ms < 阈值 1s
        repeat(5) {
            events.emit(ProtocolEvent.TextDelta("x"))
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
        }
        events.emit(completed())
        runCurrent()

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result.await())
    }

    @Test
    fun longThinkingWithRegularDeltasDoesNotTimeOut() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val request = loopRequest(events).copy(idleTimeoutSeconds = 1)
        val result = async { runLoop(request) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        // 长思考：thinking delta 每 500ms 一个，持续 3s（间隔 < 阈值，不误杀）
        repeat(6) {
            events.emit(ProtocolEvent.ThinkingDelta("t"))
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
        }
        events.emit(completed())
        runCurrent()

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result.await())
    }

    @Test
    fun silenceBeyondThresholdCommitsPartialAndReturnsIdleTimeout() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val request = loopRequest(events, onCommit = { commits += it }).copy(idleTimeoutSeconds = 1)
        val result = async { runLoop(request, emitted) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        events.emit(ProtocolEvent.TextDelta("par"))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TurnResult.IdleTimeout, result.await())
        // G7：超时也写入——partial 消息已 commit 进历史
        assertEquals(1, commits.size)
        assertEquals("par", textOf((commits.single().single() as Message.Assistant).message))
        // TurnIdleTimeout 事件携带 partial 内容
        val idleEvent = emitted.filterIsInstance<TurnEvent.TurnIdleTimeout>().single()
        assertEquals("par", textOf(idleEvent.message))
    }

    @Test
    fun keepAliveLinesDoNotResetIdleTimer() = runTest {
        // 新定义核心差异（G7）：网络层活跃（keep-alive 注释行持续到达）不算
        // agent 活跃度——真实协议解析下 keep-alive 不产出 ProtocolEvent，仍超时
        val protocol = DeepSeekChatCompletionProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        val lines = flow {
            repeat(20) {
                emit(SseLine(null)) // keep-alive 注释行，每 100ms 到达
                delay(100)
            }
        }
        val engine = FakeHttpEngine().apply {
            streamResult = {
                StreamResponse.Ok(200, mapOf("Content-Type" to "text/event-stream"), lines)
            }
        }
        val request = loopRequest(emptyFlow(), engine = engine).copy(
            protocolMapper = mapper,
            idleTimeoutSeconds = 1
        )
        val result = runLoop(request)

        // keep-alive 持续 2s（> 阈值 1s）但无 agent 事件 → idle 超时
        assertEquals(TurnResult.IdleTimeout, result)
    }

    @Test
    fun emptyLinesAlsoNotAgentActivity() = runTest {
        // 空行（SSE 事件边界）同样不算 agent 活跃度
        val protocol = DeepSeekChatCompletionProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        val lines = flow {
            repeat(20) {
                emit(SseLine("")) // 空行
                delay(100)
            }
        }
        val engine = FakeHttpEngine().apply {
            streamResult = {
                StreamResponse.Ok(200, mapOf("Content-Type" to "text/event-stream"), lines)
            }
        }
        val request = loopRequest(emptyFlow(), engine = engine).copy(
            protocolMapper = mapper,
            idleTimeoutSeconds = 1
        )
        assertEquals(TurnResult.IdleTimeout, runLoop(request))
    }

    @Test
    fun slowToolExecutionDoesNotTriggerIdle() = runTest {
        // 工具运行期间不计 idle（G7）：工具执行 3s > 阈值 1s，不误报
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor().apply { executeDelayMs = 3_000 }
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
            idleTimeoutSeconds = 1
        )
        val result = runLoop(request)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun nullIdleTimeoutDoesNotInterruptSilentStream() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val request = loopRequest(events) // idleTimeoutSeconds = null：不检测
        val result = async { runLoop(request) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        advanceTimeBy(5_000) // 静默 5s
        runCurrent()
        assertTrue(result.isActive) // 不超时

        events.emit(completed())
        runCurrent()
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result.await())
    }

    @Test
    fun zeroIdleTimeoutDisablesDetection() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val request = loopRequest(events).copy(idleTimeoutSeconds = 0) // ≤0 = 不检测
        val result = async { runLoop(request) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(result.isActive)

        events.emit(completed())
        runCurrent()
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result.await())
    }

    @Test
    fun idleTimeoutIsTerminalNotRetried() = runTest {
        // idle 超时是独立终态：不重试（即使回合层配置了）、无 RetryScheduled
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val emitted = mutableListOf<TurnEvent>()
        val request = loopRequest(events).copy(
            idleTimeoutSeconds = 1,
            options = LoopOptions(turnRetryPolicy = RetryPolicy(maxAttempts = 3))
        )
        val result = async { runLoop(request, emitted) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TurnResult.IdleTimeout, result.await())
        assertTrue(emitted.none { it is TurnEvent.RetryScheduled })
        assertEquals(1, emitted.count { it is TurnEvent.TurnIdleTimeout })
        assertTrue(emitted.none { it is TurnEvent.TurnFailed })
    }

    @Test
    fun idleTimerResetsOnEveryAgentEvent() = runTest {
        // 每个 ProtocolEvent 到达都重置计时：事件密度不均匀也不误杀
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val request = loopRequest(events).copy(idleTimeoutSeconds = 1)
        val result = async { runLoop(request) }
        runCurrent() // producer 订阅（SharedFlow replay=0，须先订阅再 emit）

        // t=0 活跃；t=900ms 再活跃（接近但未超阈值）；t=1800ms 活跃（重置后 900ms）
        events.emit(ProtocolEvent.TextDelta("a"))
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        events.emit(ProtocolEvent.TextDelta("b"))
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        events.emit(ProtocolEvent.TextDelta("c"))
        runCurrent()
        events.emit(completed())
        runCurrent()

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result.await())
    }
}
