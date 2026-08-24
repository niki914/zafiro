package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具循环测试（T6）：模型 ↔ 工具循环主路径、并发执行与保序提交、
 * 事件序列、失败路径与边界防御。断言公开面可观察行为（事件序列 /
 * commit 内容 / TurnResult 终态 / executor 调用记录），不依赖实现内部结构。
 */
class RealAgentLoopToolLoopTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun toolCall(id: String = "call1", name: String = "tool", args: String = "{}") =
        ContentBlock.ToolCall(id, name, args)

    private fun assistant(text: String? = null, vararg calls: ContentBlock.ToolCall): Message.Assistant =
        Message.Assistant(
            AssistantMessage(
                content = buildList {
                    text?.let { add(ContentBlock.Text(it)) }
                    addAll(calls)
                },
                stopReason = if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Stop
            )
        )

    private fun protocolEvent(vararg events: ProtocolEvent): List<ProtocolEvent> = events.toList()

    private fun loopRequest(
        events: List<ProtocolEvent>,
        toolRegistry: ToolRegistry = DefaultToolRegistry(),
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
        toolRegistry = toolRegistry,
        protocolMapper = FakeProtocolMapper(events),
        hooks = emptyList(),
        httpEngine = FakeHttpEngine(),
        retryPolicy = com.niki914.okia.error.RetryPolicy(),
        onCommit = onCommit
    )

    private fun loopRequest(
        events: Flow<ProtocolEvent>,
        toolRegistry: ToolRegistry = DefaultToolRegistry(),
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
        toolRegistry = toolRegistry,
        protocolMapper = FakeProtocolMapper(events),
        hooks = emptyList(),
        httpEngine = FakeHttpEngine(),
        retryPolicy = com.niki914.okia.error.RetryPolicy(),
        onCommit = onCommit
    )

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    private fun eventTypes(emitted: List<TurnEvent>): List<String> =
        emitted.map { it::class.simpleName!!.removePrefix("TurnEvent\$") }

    private fun toolResultOf(message: Message): Message.ToolResult = message as Message.ToolResult

    // ── 工具循环主路径 ─────────────────────────────────────────────────────

    @Test
    fun toolUseExecutesThenNextRoundTextCompletesTurn() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", "{\"q\":1}"),
                    ProtocolEvent.ToolCallReady("call1", "tool", "{\"q\":1}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.TextDelta("answer"),
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )

        val result = runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }.copy(protocolMapper = mapper),
            emitted
        )

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // 工具执行一次，参数 = 模型产出
        assertEquals(1, executor.calls.size)
        assertEquals("{\"q\":1}", executor.calls.single().argumentsJson)
        // 提交顺序：第一轮 Assistant(ToolCall) + 第二批 ToolResult + 第三批 Assistant(Text)
        assertEquals(3, commits.size)
        val assistant1 = commits[0].single() as Message.Assistant
        assertEquals(StopReason.ToolUse, assistant1.message.stopReason)
        assertEquals(listOf("tool"), assistant1.message.content.filterIsInstance<ContentBlock.ToolCall>().map { it.name })
        val toolResult = toolResultOf(commits[1].single())
        assertEquals("call1", toolResult.callId)
        assertEquals(ToolCallOutcome.Success("ok"), toolResult.outcome)
        val assistant2 = commits[2].single() as Message.Assistant
        assertEquals("answer", (assistant2.message.content.single() as ContentBlock.Text).text)
    }

    @Test
    fun secondRoundHistoryIncludesToolResult() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.TextDelta("answer"),
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        // 第二轮 buildRequest 的 history 含 [User, Assistant(ToolCall), ToolResult]
        val second = mapper.builtHistories[1]
        assertEquals(3, second.size)
        assertTrue(second[1] is Message.Assistant)
        assertTrue(second[2] is Message.ToolResult)
    }

    @Test
    fun toolCallEventStreamSequence() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", "{\"q\":1}"),
                    ProtocolEvent.ToolCallReady("call1", "tool", "{\"q\":1}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper), emitted)

        assertEquals(
            listOf(
                "TurnStarted", "ToolCallStarted", "ToolCallDelta", "ToolCallReady",
                "ToolRunning", "ToolSucceeded", "TurnCompleted"
            ),
            eventTypes(emitted)
        )
        // ToolCallReady 携带最终参数
        val ready = emitted.filterIsInstance<TurnEvent.ToolCallReady>().single()
        assertEquals("{\"q\":1}", ready.toolCall.argumentsJson)
        // ToolSucceeded 携带 outcome
        val succeeded = emitted.filterIsInstance<TurnEvent.ToolSucceeded>().single()
        assertEquals(ToolCallOutcome.Success("ok"), succeeded.outcome)
    }

    @Test
    fun multipleToolCallsCommitInOrder() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", "{\"i\":1}"),
                    ProtocolEvent.ToolCallReady("call1", "tool", "{\"i\":1}"),
                    ProtocolEvent.ToolCallStarted("call2", "tool"),
                    ProtocolEvent.ToolCallDelta("call2", "tool", "{\"i\":2}"),
                    ProtocolEvent.ToolCallReady("call2", "tool", "{\"i\":2}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }.copy(protocolMapper = mapper),
            emitted
        )

        // 两个调用都执行，参数正确
        assertEquals(2, executor.calls.size)
        assertEquals("{\"i\":1}", executor.calls[0].argumentsJson)
        assertEquals("{\"i\":2}", executor.calls[1].argumentsJson)
        // ToolResult 批量原子提交，按调用顺序
        val toolResults = commits[1].map { toolResultOf(it) }
        assertEquals(listOf("call1", "call2"), toolResults.map { it.callId })
        // 事件保序：ToolRunning ×2 先于 Succeeded ×2
        val running = emitted.filterIsInstance<TurnEvent.ToolRunning>()
        val succeeded = emitted.filterIsInstance<TurnEvent.ToolSucceeded>()
        assertEquals(2, running.size)
        assertEquals(2, succeeded.size)
        val order = eventTypes(emitted)
        assertTrue(order.indexOf("ToolRunning") < order.indexOf("ToolSucceeded"))
    }

    @Test
    fun toolLoopMultipleRounds() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("c1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.ToolCallReady("c2", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )

        val result = runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        assertEquals(2, executor.calls.size)
        // 三轮 buildRequest
        assertEquals(3, mapper.builtHistories.size)
    }

    @Test
    fun toolThenLengthCompletesWithLength() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Length))
            )
        )

        val result = runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        assertEquals(TurnResult.Completed(CompletionReason.Length), result)
    }

    // ── 失败路径与边界防御 ─────────────────────────────────────────────────

    @Test
    fun unknownToolFeedsBackFailureAndContinues() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("known"), executor) }
        val emitted = mutableListOf<TurnEvent>()
        val commits = mutableListOf<List<Message>>()
        // 第一轮：模型调用未注册的 missing（命名错误）→ Failure 结果回喂；
        // 第二轮：模型未再发起调用（对照派：收到失败后正常收尾）→ Stop 结束。
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "missing", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )

        val result = runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }.copy(protocolMapper = mapper),
            emitted
        )

        // 未知工具 = 工具级失败回喂：回合正常完成、未执行任何真实工具、无回合失败事件
        assertTrue(result is TurnResult.Completed)
        assertTrue(executor.calls.isEmpty())
        assertTrue(emitted.none { it is TurnEvent.TurnFailed })

        // ToolFailed 事件携带纯文本错误（默认文案；message 与 content 同值，
        // message 供 UI / content 回喂模型）
        val failedEvent = emitted.filterIsInstance<TurnEvent.ToolFailed>().single()
        val outcome = failedEvent.outcome as ToolCallOutcome.Failure
        assertEquals("Unknown tool 'missing'", outcome.message)
        assertEquals("Unknown tool 'missing'", outcome.content)

        // 提交顺序（消息级, onCommit）：第一批 = 第一轮 Assistant（含 ToolCall）→
        // 第二批 = ToolResult 回喂（callId 与调用一致，模型据此自纠）→
        // 第三批 = 第二轮 Assistant（空内容，Stop 收尾）
        assertEquals(3, commits.size)
        val assistant1 = commits[0].single() as Message.Assistant
        assertEquals(
            listOf("missing"),
            assistant1.message.content.filterIsInstance<ContentBlock.ToolCall>().map { it.name }
        )
        val toolResult = toolResultOf(commits[1].single())
        assertEquals("call1", toolResult.callId)
        assertEquals("missing", toolResult.toolName)

        // 第二轮请求历史以该 ToolResult 结尾（回喂生效）
        val secondRoundHistory = mapper.builtHistories[1]
        assertEquals("missing", (secondRoundHistory.last() as Message.ToolResult).toolName)
    }

    @Test
    fun toolUseWithoutToolCallBlockFallsBackToStop() = runTest {
        // 防御：ToolUse 但 content 无 ToolCall 块（协议不一致）→ 按 Stop 结束，避免死循环
        val mapper = FakeProtocolMapper(
            listOf(ProtocolEvent.Completed(stopReason = StopReason.ToolUse))
        )
        val result = runLoop(loopRequest(emptyList()).copy(protocolMapper = mapper))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun executorExceptionFailsTurnWithToolExecutionFailed() = runTest {
        val executor = RecordingToolExecutor().apply { executeError = RuntimeException("executor bug") }
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
            )
        )

        val result = runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.ToolExecutionFailed, failed.error.code)
        assertTrue(failed.error.message.contains("tool"))
        assertTrue(failed.error.message.contains("execution failed"))
        // 单工具异常也记录执行了（异常发生在执行中）
        assertEquals(1, executor.calls.size)
    }

    @Test
    fun streamErrorInSecondRoundFailsTurn() = runTest {
        // 工具循环中第二轮传输失败：回合 Failed，第一轮产出已 commit（事实保留）
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val commits = mutableListOf<List<Message>>()
        val engine = FakeHttpEngine()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                emptyList() // 第二轮无 Completed → Parse 失败
            )
        )

        val result = runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }
                .copy(protocolMapper = mapper, httpEngine = engine),
            mutableListOf()
        )
        // 第二轮空流 → stream 正常返回但无 Completed → Parse 失败
        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.Parse, failed.error.code)
        // 第一轮产出已提交：Assistant + ToolResult
        assertEquals(2, commits.size)
    }

    // ── 并发 ───────────────────────────────────────────────────────────────

    @Test
    fun toolExecutionRunsConcurrently() = runTest {
        // 两个 100ms 工具并发执行：虚拟时间总耗时 100ms（非 200ms 串行）
        val executor1 = RecordingToolExecutor().apply { executeDelayMs = 100 }
        val executor2 = RecordingToolExecutor().apply { executeDelayMs = 100 }
        val registry = DefaultToolRegistry().apply {
            register(localTool("t1"), executor1)
            register(localTool("t2"), executor2)
        }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("c1", "t1", "{}"),
                    ProtocolEvent.ToolCallReady("c2", "t2", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        assertEquals(1, executor1.calls.size)
        assertEquals(1, executor2.calls.size)
        // 虚拟时间证明并行：两个 100ms 并发 → 总虚拟时间 100（runTest 结束后 currentTime）
        assertTrue(currentTime <= 100L)
    }

    @Test
    fun cancellationDuringToolExecutionPropagates() = runTest {
        val executor = RecordingToolExecutor().apply { executeDelayMs = 10_000 }
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val events = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
            )
        )
        var caught: CancellationException? = null
        val job = launch {
            try {
                RealAgentLoop().run(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = events)) {}
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent()
        // 等工具执行开始（async 启动后取消）
        while (executor.calls.isEmpty()) runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(caught != null)
    }
}
