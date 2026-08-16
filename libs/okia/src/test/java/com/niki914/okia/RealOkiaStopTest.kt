package com.niki914.okia

import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeAgentLoop
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.StubMcpClient
import com.niki914.okia.fake.localTool
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.RealAgentLoop
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 kill-then-stop 门面测试（G1/G2/G3 裁决 + §5.11）：
 * beforeStop 在取消回合 job 前调用，calls = 本回合已提交 Assistant 中的
 * ToolCall 块（回合起点之后推导）；外部取消与 stop 表现一致（都触发 kill）；
 * 并发/重入 stop 至多一次 kill；hook 异常不中止停止流程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealOkiaStopTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun completed(stopReason: StopReason? = StopReason.Stop) =
        ProtocolEvent.Completed(stopReason = stopReason)

    private fun deps(
        mapper: ProtocolCompatMapper,
        loop: AgentLoop = RealAgentLoop(),
        engine: HttpEngine = FakeHttpEngine()
    ) = object : OkiaDependencies {
        override val agentLoop = loop
        override val protocolMapper = mapper
        override val mcpClient = StubMcpClient
    }

    private fun openOkia(
        mapper: ProtocolCompatMapper,
        engine: HttpEngine = FakeHttpEngine(),
        loop: AgentLoop = RealAgentLoop(),
        registry: ToolRegistry = DefaultToolRegistry(),
        hooks: List<Hooks> = emptyList(),
        idleTimeoutSeconds: Long? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ): RealOkia = RealOkia(
        dependencies = deps(mapper, loop, engine),
        restore = null,
        config = OkiaConfig.Builder().apply {
            endpoint = "https://api.test/v1"
            apiKey = "test-key"
            model = "test-model"
            httpEngine = engine
            toolRegistry = registry
            this.hooks = hooks
            this.idleTimeoutSeconds = idleTimeoutSeconds
        }.build(),
        turnScope = scope
    )

    private fun testScope(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    /** 记录 beforeStop 收到的 calls，可注入异常。 */
    private class StopRecordingHooks(
        private val stopCalls: MutableList<List<ContentBlock.ToolCall>>,
        private val throwOnStop: Boolean = false
    ) : Hooks {
        override suspend fun beforeStop(calls: List<ContentBlock.ToolCall>) {
            stopCalls += calls
            if (throwOnStop) throw RuntimeException("kill hook boom")
        }
    }

    // ── C. kill-then-stop（beforeStop 推导与调用） ────────────────────────

    @Test
    fun stopDeliversDispatchedToolCallsToBeforeStop() = runTest {
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor()
        val gate = CompletableDeferred<Unit>()
        executor.onExecute = { gate.await() } // 工具执行挂起
        registry.register(localTool("t1"), executor)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(
                listOf(listOf(ProtocolEvent.ToolCallReady("c1", "t1", "{}"), completed(StopReason.ToolUse)))
            ),
            registry = registry,
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent() // 工具执行挂起中

        okia.stop()

        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        // beforeStop 收到本回合已派发的工具调用（多轮推导见下条测试）
        assertEquals(1, stopCalls.size)
        assertEquals("t1", stopCalls.single().single().name)
        assertEquals("c1", stopCalls.single().single().id)
        okia.close()
    }

    @Test
    fun beforeStopDeliversAllDispatchedCallsAcrossToolRounds() = runTest {
        // 两轮工具调用：beforeStop 收到两个 ToolCall（起点之后全部已提交调用）
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor()
        val gate = CompletableDeferred<Unit>()
        var executeCount = 0
        executor.onExecute = {
            executeCount++
            if (executeCount >= 2) gate.await() // 第二轮工具执行挂起
        }
        registry.register(localTool("t1"), executor)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(
                listOf(
                    listOf(ProtocolEvent.ToolCallReady("c1", "t1", "{}"), completed(StopReason.ToolUse)),
                    listOf(ProtocolEvent.ToolCallReady("c2", "t1", "{}"), completed(StopReason.ToolUse)),
                    listOf(completed())
                )
            ),
            registry = registry,
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent()
        runCurrent() // 第一轮工具执行完，第二轮流收集完成、工具执行挂起

        okia.stop()

        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        assertEquals(listOf("c1", "c2"), stopCalls.single().map { it.id })
        okia.close()
    }

    @Test
    fun stopWithoutDispatchedToolsPassesEmptyCalls() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(events),
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent()

        okia.stop()

        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        assertEquals(1, stopCalls.size)
        assertTrue(stopCalls.single().isEmpty())
        okia.close()
    }

    @Test
    fun beforeStopHookExceptionDoesNotAbortStop() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(
            FakeProtocolMapper(events),
            hooks = listOf(StopRecordingHooks(mutableListOf(), throwOnStop = true)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent()

        okia.stop() // hook 抛异常被捕获，不中止停止流程

        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        okia.close()
    }

    @Test
    fun concurrentStopsInvokeBeforeStopOnce() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(events),
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent()

        val s1 = launch { okia.stop() }
        val s2 = launch { okia.stop() }
        s1.join()
        s2.join()
        runCurrent()

        // G2：并发 stop 至多一次 kill
        assertEquals(1, stopCalls.size)
        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        okia.close()
    }

    @Test
    fun externalCancellationTriggersBeforeStopAndRethrows() = runTest {
        // G1：外部取消与 stop 表现一致——都触发 beforeStop（kill 步骤），
        // 区别是终态表达：stop → Aborted，外部取消 → 传播 CancellationException
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor()
        val gate = CompletableDeferred<Unit>()
        executor.onExecute = { gate.await() }
        registry.register(localTool("t1"), executor)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(
                listOf(listOf(ProtocolEvent.ToolCallReady("c1", "t1", "{}"), completed(StopReason.ToolUse)))
            ),
            registry = registry,
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        var caught: CancellationException? = null
        val sendJob = launch {
            try {
                okia.send("hi") { }
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent() // 工具执行挂起中

        sendJob.cancel()
        sendJob.join()

        assertEquals(1, stopCalls.size) // 外部取消也触发 kill
        assertTrue(caught != null)
        okia.close()
    }

    @Test
    fun streamingUncommittedToolCallNotInBeforeStopCalls() = runTest {
        // C6：只含已提交调用——流式中未 Ready / 未 Completed 的调用不进 calls
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val stopCalls = mutableListOf<List<ContentBlock.ToolCall>>()
        val okia = openOkia(
            FakeProtocolMapper(events),
            hooks = listOf(StopRecordingHooks(stopCalls)),
            scope = testScope(testScheduler)
        )
        val sendJob = async { okia.send("hi") { } }
        runCurrent()
        events.emit(ProtocolEvent.ToolCallStarted("c1", "t1")) // 流式中，未 commit
        runCurrent()

        okia.stop()

        assertEquals(TurnResult.Aborted(StopCause.UserStop), sendJob.await())
        assertTrue(stopCalls.single().isEmpty())
        okia.close()
    }

    @Test
    fun stopWithNoActiveTurnIsNoOp() = runTest {
        val okia = openOkia(FakeProtocolMapper(listOf(completed())), scope = testScope(testScheduler))
        // 无活跃回合时 stop() 直接返回，无副作用
        okia.stop()
        okia.close()
    }

    @Test
    fun stopThenSendStartsFreshTurn() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val send1 = async { okia.send("first") { } }
        runCurrent()

        okia.stop()
        assertEquals(TurnResult.Aborted(StopCause.UserStop), send1.await())

        // stop 清理干净：新回合正常启动（同一 SharedFlow，新收集）
        val send2 = async { okia.send("second") { } }
        runCurrent()
        events.emit(completed())
        runCurrent()
        assertEquals(TurnResult.Completed(CompletionReason.Stop), send2.await())
        okia.close()
    }

    // ── idle 门面级（D7）：超时后历史包含 partial、live 清空 ──────────────

    @Test
    fun idleTimeoutLeavesPartialInHistoryAndClearsLive() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), idleTimeoutSeconds = 1, scope = testScope(testScheduler))
        val sendJob = async {
            okia.send(
                "hi",
                onEvent = {}
            )
        }
        runCurrent()
        events.emit(ProtocolEvent.TextDelta("half"))
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TurnResult.IdleTimeout, sendJob.await())
        // partial 消息在历史（G7：超时也写入），live 清空
        val history = okia.conversation.value.history
        assertEquals(2, history.size) // User + partial Assistant
        val assistant = history[1].message as Message.Assistant
        assertEquals("half", (assistant.message.content.single() as ContentBlock.Text).text)
        assertTrue(okia.conversation.value.live == null)
        okia.close()
    }
}
