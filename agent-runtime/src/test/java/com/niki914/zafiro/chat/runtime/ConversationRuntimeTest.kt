package com.niki914.zafiro.chat.runtime

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.agentic.shell.AuthorizationContext
import com.niki914.zafiro.chat.agentic.shell.TOOL_CONFIRM_CHANNEL_BACKGROUND
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group9：新运行时状态不变量、来源寿命与竞争测试（T-20，AC2/AC5/AC9）。
 *
 * 全部用例驱动真实 [ConversationStateReducer]/[ConversationExecutor]/[ConversationRuntime]
 * 与真实事实模型（[RuntimeFact]/[TurnEvent]），只替换 [ConversationEngine]（设计声明的
 * 执行接缝）与 owner Job。时序由 StandardTestDispatcher + Deferred 关卡控制，无任意 sleep。
 * 本文件只写测试，不执行（终审统一跑 gradle）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRuntimeTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    /** 可控执行引擎：记录收集次数/发射器与定向停止调用，执行体由测试注入。 */
    private class FakeEngine : ConversationEngine {
        override val conversation = MutableStateFlow<Conversation?>(null)
        val stopTurns = mutableListOf<TurnKey>()
        var stopTurnResult = false
        var streamCalls = 0
        val emitters = mutableListOf<BoundFactEmitter>()
        /** 执行协程 Job（stream 体所在协程），用于在 stopTurn 调用点观测取消先后。 */
        val executionJobs = mutableMapOf<TurnKey, Job>()
        /** 每次 engine.stopTurn 调用时目标执行 Job 是否仍活跃（Home 应为 true，Host 应为 false）。 */
        val stopTurnJobActive = mutableListOf<Boolean>()
        var streamBody: suspend (BoundFactEmitter, suspend (LlmStreamEvent) -> Unit) -> Unit =
            { emitter, _ -> emitter.emit(TurnResult.Completed(CompletionReason.Stop)) }

        override fun stream(
            query: String,
            images: List<ContentBlock.Image>,
            observer: RuntimeFactSink,
        ): Flow<LlmStreamEvent> = flow {
            streamCalls++
            val emitter = observer as BoundFactEmitter
            emitters += emitter
            executionJobs[emitter.scope.key] = coroutineContext[Job]!!
            streamBody(emitter) { emit(it) }
        }

        override suspend fun stopTurn(turn: TurnKey): Boolean {
            stopTurns += turn
            stopTurnJobActive += executionJobs[turn]?.isActive ?: false
            return stopTurnResult
        }
    }

    private fun TestScope.owner(id: String, source: EntrySource): ExecutionOwner =
        ExecutionOwner(
            id = id,
            source = source,
            parentJob = Job(parent = backgroundScope.coroutineContext[Job]),
            executionContext = StandardTestDispatcher(testScheduler),
        )

    private fun turnKey(turnId: String = "t1", epoch: Long = 1L, conversationId: String = "c1") =
        TurnKey(conversationId, turnId, epoch)

    private fun partial(vararg blocks: ContentBlock) = AssistantMessage(blocks.toList())

    private fun observation(
        requestId: String,
        turn: TurnKey,
        phase: PermissionPhase,
        channel: String? = null,
        toolCallId: String? = null,
        kind: PermissionKind = PermissionKind.ToolConfirmation,
        reason: Reason? = null,
    ) = PermissionRequestObservation(
        requestId = requestId,
        turn = turn,
        toolCallId = toolCallId,
        kind = kind,
        target = "tool",
        channel = channel,
        phase = phase,
        reason = reason,
    )

    // ── reducer：初始态、原始事实完整性 ────────────────────────────────────

    @Test
    fun initialSnapshotIsEmptyAndIdle() {
        val reducer = ConversationStateReducer { 7L }
        val snapshot = reducer.snapshot.value
        assertEquals(0L, snapshot.version)
        assertEquals(7L, snapshot.updatedAtMillis)
        assertNull(snapshot.conversation)
        assertNull(snapshot.active)
        assertNull(snapshot.lastTerminal)
        assertEquals(SessionObservation(null, null, null, OperationPhase.None, null), snapshot.session)
    }

    @Test
    fun rawStreamFactsExposeThinkingTextToolArgumentsAndResults() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TurnStarted("q")))
        val partial = partial(ContentBlock.Thinking("th"))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ThinkingStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ThinkingDelta(0, "th", partial)))
        reducer.accept(
            RuntimeFact.StreamEvent(key, 1, TurnEvent.ThinkingEnded(0, "thinking done", partial)),
        )
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextStarted(1, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextDelta(1, "he", partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextEnded(1, "hello", partial)))
        reducer.accept(
            RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallStarted(2, partial, "call-1", "shell")),
        )
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallDelta(2, "{\"cmd\":", partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallDelta(2, "\"ls\"}", partial)))

        // Ready 之前：参数增量已可见且可能不是合法 JSON，不伪造块位置。
        val beforeReady = requireNotNull(reducer.snapshot.value.active)
        assertEquals(TurnPhase.Running, beforeReady.phase)
        assertEquals(listOf(BlockKind.Thinking, BlockKind.Text), beforeReady.blocks.map { it.kind })
        assertEquals(
            "thinking done",
            (beforeReady.blocks.first { it.kind == BlockKind.Thinking }.content as ContentBlock.Thinking).text,
        )
        assertEquals(
            "hello",
            (beforeReady.blocks.first { it.kind == BlockKind.Text }.content as ContentBlock.Text).text,
        )
        val pendingTool = beforeReady.tools.single()
        assertEquals(ToolPhase.Arguments, pendingTool.phase)
        assertEquals("call-1", pendingTool.callId)
        assertEquals("shell", pendingTool.call!!.name)
        assertEquals("{\"cmd\":\"ls\"}", pendingTool.call.argumentsJson)
        assertNull(pendingTool.outcome)

        val ready = ContentBlock.ToolCall("call-1", "shell", "{\"cmd\":\"ls\"}")
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallReady(2, ready, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolRunning(2, ready, partial)))
        val running = requireNotNull(reducer.snapshot.value.active)
        assertTrue(
            running.blockers.any { it.kind == BlockerKind.ToolExecution && it.toolCallId == "call-1" },
        )
        reducer.accept(
            RuntimeFact.StreamEvent(
                key, 1, TurnEvent.ToolSucceeded(2, ready, ToolCallOutcome.Success("ok"), partial),
            ),
        )
        val done = requireNotNull(reducer.snapshot.value.active)
        assertEquals(ToolPhase.Succeeded, done.tools.single().phase)
        assertEquals(ToolCallOutcome.Success("ok"), done.tools.single().outcome)
        assertTrue(done.blockers.isEmpty())
    }

    @Test
    fun blockKeysUseTurnAttemptOrdinalAndIndexAcrossSegments() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        val partial = partial(ContentBlock.Text(""))
        val ready = ContentBlock.ToolCall("call-1", "shell", "{}")

        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextEnded(0, "a", partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallReady(1, ready, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolRunning(1, ready, partial)))
        reducer.accept(
            RuntimeFact.StreamEvent(
                key, 1, TurnEvent.ToolSucceeded(1, ready, ToolCallOutcome.Success("ok"), partial),
            ),
        )
        // 工具循环下一段：OKIA 段首 index 从 0 重计 → 归约进入新 messageOrdinal。
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextEnded(0, "b", partial)))

        val blocks = requireNotNull(reducer.snapshot.value.active).blocks
        assertEquals(listOf(0, 0, 1), blocks.map { it.key.messageOrdinal })
        assertEquals(listOf(0, 1, 0), blocks.map { it.key.index })
        assertEquals(3, blocks.map { it.key }.toSet().size)
        assertEquals(listOf(1, 1, 1), blocks.map { it.key.attempt })
    }

    @Test
    fun retryDiscardsOnlyUncommittedAttemptView() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        val partial = partial(ContentBlock.Text(""))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextDelta(0, "partial", partial)))
        reducer.accept(
            RuntimeFact.StreamEvent(key, 1, TurnEvent.RetryScheduled(2, 3, 500, "transport")),
        )

        val afterRetry = requireNotNull(reducer.snapshot.value.active)
        assertTrue(afterRetry.blocks.isEmpty())
        assertEquals(1, afterRetry.attempt)
        val backoff = afterRetry.blockers.single()
        assertEquals(BlockerKind.RetryBackoff, backoff.kind)
        assertEquals(500L, backoff.retryDelayMs)
        assertEquals("transport", backoff.reason?.code)

        // 新尝试的首个内容事件：退避阻塞被清除，内容替换为最终段落。
        reducer.accept(RuntimeFact.StreamEvent(key, 2, TurnEvent.TextStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 2, TurnEvent.TextEnded(0, "final", partial)))
        val recovered = requireNotNull(reducer.snapshot.value.active)
        assertEquals(2, recovered.attempt)
        assertTrue(recovered.blockers.isEmpty())
        assertEquals(1, recovered.blocks.size)
        assertEquals("final", (recovered.blocks.single().content as ContentBlock.Text).text)
    }

    @Test
    fun retryAfterCommittedSegmentPreservesPriorContent() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        val partial = partial(ContentBlock.Text(""))
        val ready = ContentBlock.ToolCall("call-1", "shell", "{}")
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolCallReady(0, ready, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.ToolRunning(0, ready, partial)))
        reducer.accept(
            RuntimeFact.StreamEvent(
                key, 1, TurnEvent.ToolSucceeded(0, ready, ToolCallOutcome.Success("ok"), partial),
            ),
        )
        // commit 后的段首发送失败重试：已提交段落证据（工具终态）必须保留。
        reducer.accept(
            RuntimeFact.StreamEvent(key, 1, TurnEvent.RetryScheduled(2, 3, 100, "send failed")),
        )
        val kept = requireNotNull(reducer.snapshot.value.active)
        assertEquals(1, kept.blocks.size)
        assertEquals(ToolPhase.Succeeded, kept.tools.single().phase)

        // 下一段文本 index 从 0 重计：边界推进 ordinal，两段内容都在。
        reducer.accept(RuntimeFact.StreamEvent(key, 2, TurnEvent.TextStarted(0, partial)))
        reducer.accept(RuntimeFact.StreamEvent(key, 2, TurnEvent.TextEnded(0, "next", partial)))
        val after = requireNotNull(reducer.snapshot.value.active)
        assertEquals(listOf(0, 1), after.blocks.map { it.key.messageOrdinal })
        assertEquals(1, after.tools.size)
    }

    @Test
    fun permissionsBlockersRetryAndPreparationAreModeledAndCleared() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))

        reducer.accept(
            RuntimeFact.PermissionReported(observation("r1", key, PermissionPhase.Requested)),
        )
        var active = requireNotNull(reducer.snapshot.value.active)
        assertEquals(PermissionPhase.Requested, active.permissions.single().phase)
        assertEquals(BlockerKind.Permission, active.blockers.single().kind)
        assertEquals("r1", active.blockers.single().requestId)

        reducer.accept(
            RuntimeFact.PermissionReported(
                observation("r1", key, PermissionPhase.Processing, channel = "foreground"),
            ),
        )
        active = requireNotNull(reducer.snapshot.value.active)
        assertEquals("foreground", active.permissions.single().channel)
        assertEquals(TurnPhase.Waiting, active.phase)

        reducer.accept(
            RuntimeFact.PermissionReported(observation("r1", key, PermissionPhase.Allowed)),
        )
        active = requireNotNull(reducer.snapshot.value.active)
        assertEquals(PermissionPhase.Allowed, active.permissions.single().phase)
        assertTrue(active.blockers.isEmpty())

        reducer.accept(RuntimeFact.CapabilityPreparationStarted(key))
        active = requireNotNull(reducer.snapshot.value.active)
        assertEquals(BlockerKind.CapabilityPreparation, active.blockers.single().kind)
        reducer.accept(RuntimeFact.CapabilityPreparationEnded(key))
        assertTrue(requireNotNull(reducer.snapshot.value.active).blockers.isEmpty())

        reducer.accept(
            RuntimeFact.StreamEvent(key, 1, TurnEvent.RetryScheduled(2, 3, 250, "rate limit")),
        )
        active = requireNotNull(reducer.snapshot.value.active)
        assertEquals(BlockerKind.RetryBackoff, active.blockers.single().kind)
        assertEquals(250L, active.blockers.single().retryDelayMs)
        // 新内容事件结束退避。
        reducer.accept(
            RuntimeFact.StreamEvent(key, 2, TurnEvent.TextStarted(0, partial(ContentBlock.Text("")))),
        )
        assertTrue(requireNotNull(reducer.snapshot.value.active).blockers.isEmpty())
    }

    @Test
    fun cancellationRequestMarksCancellingAndKeepsEngineTerminalReasonFirst() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        val partial = partial(ContentBlock.Text(""))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TextStarted(0, partial)))
        reducer.accept(
            RuntimeFact.CancellationRequested(
                key, Reason("STOP_REQUEST", "EXECUTOR"), StopTrigger.OwnerCleared,
            ),
        )
        val cancelling = requireNotNull(reducer.snapshot.value.active)
        assertEquals(TurnPhase.Cancelling, cancelling.phase)
        assertEquals("STOP_REQUEST", cancelling.reason?.code)

        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TurnAborted(partial, StopCause.UserStop)))
        val settled = requireNotNull(reducer.snapshot.value.active)
        assertEquals(TurnPhase.Cancelled, settled.phase)
        assertEquals("UserStop", settled.reason?.code)
        assertEquals(BlockPhase.Interrupted, settled.blocks.single().phase)
        assertTrue(settled.blockers.isEmpty())
    }

    @Test
    fun terminalPhaseIsAppliedOnceAndFirstDetailedReasonIsKept() {
        val reducer = ConversationStateReducer { 1L }
        val key = turnKey()
        reducer.accept(RuntimeFact.ExecutionStarted(key, EntrySource.HomeChat, TurnInput("q")))
        val partial = partial(ContentBlock.Text(""))
        reducer.accept(RuntimeFact.StreamEvent(key, 1, TurnEvent.TurnAborted(partial, StopCause.UserStop)))
        // 后续事件级/执行级终态不得重复迁移阶段，也不覆盖已有详细原因。
        reducer.accept(
            RuntimeFact.StreamEvent(
                key, 1, TurnEvent.TurnFailed(partial, LLMError(LLMErrorCode.Transport, "late failure")),
            ),
        )
        reducer.accept(
            RuntimeFact.StreamResult(key, 1, TurnResult.Failed(LLMError(LLMErrorCode.Parse, "even later"))),
        )
        val afterEvents = requireNotNull(reducer.snapshot.value.active)
        assertEquals(TurnPhase.Cancelled, afterEvents.phase)
        assertEquals("UserStop", afterEvents.reason?.code)

        reducer.accept(RuntimeFact.StreamResult(key, 1, TurnResult.Completed(CompletionReason.Stop)))
        val settled = reducer.snapshot.value
        assertNull(settled.active)
        assertEquals(TurnPhase.Cancelled, settled.lastTerminal?.phase)
        assertEquals("UserStop", settled.lastTerminal?.reason?.code)
    }

    @Test
    fun staleStartsAndUnknownTurnFactsCannotTouchActiveTurn() {
        val reducer = ConversationStateReducer { 1L }
        val first = turnKey("t1", 1L)
        val second = turnKey("t2", 2L)
        reducer.accept(RuntimeFact.ExecutionStarted(first, EntrySource.HomeChat, TurnInput("one")))
        reducer.accept(RuntimeFact.ExecutionStarted(second, EntrySource.HomeChat, TurnInput("two")))
        val partial = partial(ContentBlock.Text(""))
        // 旧回合事实只更新其等待结算状态，不改 active。
        reducer.accept(RuntimeFact.StreamEvent(first, 1, TurnEvent.TextStarted(0, partial)))
        assertEquals(second, reducer.snapshot.value.active?.key)
        // 陈旧/重复 start 被 epoch 水位线挡住。
        reducer.accept(
            RuntimeFact.ExecutionStarted(turnKey("t0", 1L), EntrySource.HomeChat, TurnInput("stale")),
        )
        reducer.accept(RuntimeFact.ExecutionStarted(second, EntrySource.HomeChat, TurnInput("duplicate")))
        // 未跟踪身份完全忽略。
        reducer.accept(
            RuntimeFact.StreamEvent(turnKey("ghost", 99L), 1, TurnEvent.TextStarted(0, partial)),
        )
        assertEquals(second, reducer.snapshot.value.active?.key)
        // 旧回合事实只落在其等待结算状态上，不进入 active 视图。
        assertEquals(0, requireNotNull(reducer.snapshot.value.active).blocks.size)
        // 新回合结算后，仍进行中的旧回合恢复可见（其已收内容不丢）。
        reducer.accept(RuntimeFact.StreamResult(second, 1, TurnResult.Completed(CompletionReason.Stop)))
        assertEquals(first, reducer.snapshot.value.active?.key)
        assertEquals(1, requireNotNull(reducer.snapshot.value.active).blocks.size)
    }

    // ── runtime：状态出口、晚订阅与单执行点 ────────────────────────────────

    @Test
    fun sameSourceConversationIsObservedSynchronouslyIncludingIdleAndNull() = runTest {
        val engine = FakeEngine()
        engine.conversation.value = Conversation("okia-1", null, emptyList())
        val runtime = ConversationRuntime(backgroundScope, engine)
        // UNDISPATCHED init：构造线程即归约当前值，首订阅不见空窗。
        assertEquals("okia-1", runtime.snapshot.value.conversation?.id)
        assertEquals("okia-1", runtime.snapshot.value.session.runtimeConversationId)

        engine.conversation.value = null
        runCurrent()
        assertNull(runtime.snapshot.value.conversation)
        assertNull(runtime.snapshot.value.session.runtimeConversationId)
        assertEquals(0, engine.streamCalls)
    }

    @Test
    fun snapshotSubscribersAreReadOnlyAndLateSubscriberSeesLatest() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        engine.streamBody = { emitter, _ ->
            emitter.emit(TurnEvent.RetryScheduled(1, 3, 250, "transport"))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val seenA = mutableListOf<Long>()
        val seenB = mutableListOf<Long>()
        val jobA = launch { runtime.snapshot.collect { seenA += it.version } }
        val jobB = launch { runtime.snapshot.collect { seenB += it.version } }
        runCurrent()

        val handle = runtime.submit(TurnInput("q"), owner) {}
        advanceUntilIdle()

        // 多个订阅者不增加执行次数，也不触发停止。
        assertEquals(1, engine.streamCalls)
        assertTrue(engine.stopTurns.isEmpty())
        assertEquals(runtime.snapshot.value.version, seenA.last())
        assertEquals(runtime.snapshot.value.version, seenB.last())
        // 晚订阅者立即取得完整最新状态，而非只等后续增量。
        val late = runtime.snapshot.first()
        assertEquals(TurnPhase.Completed, late.lastTerminal?.phase)
        assertEquals(OperationPhase.None, late.session.phase)
        jobA.cancel()
        jobB.cancel()
        runtime.await(handle)
    }

    @Test
    fun submitRunsEveryOwnerWithoutNewBusyAdmissionGate() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val first = runtime.submit(TurnInput("one"), owner) {}
        runCurrent()
        val second = runtime.submit(TurnInput("two"), owner) {}
        runCurrent()
        // 忙时拒绝仍在原入口；executor 不新增统一队列/抢占/提前拒绝。
        assertEquals(2, engine.streamCalls)
        assertEquals(second.key, runtime.snapshot.value.active?.key)
        runtime.cancel(first.key)
        runtime.cancel(second.key)
        advanceUntilIdle()
    }

    @Test
    fun noInFlightTurnIsEvictedWhileManyArePending() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handles = (1..12).map { runtime.submit(TurnInput("q$it"), owner) {} }
        runCurrent()
        assertEquals(handles.last().key, runtime.snapshot.value.active?.key)
        // 全部在途回合都仍被跟踪：不存在有界淘汰导致丢失。
        assertTrue(handles.all { runtime.cancel(it.key) })
        advanceUntilIdle()
        assertTrue(handles.none { runtime.cancel(it.key) })
    }

    @Test
    fun newerTurnFailurePromotesOlderStillRunningTurn() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
        }
        val older = runtime.submit(TurnInput("older"), owner) {}
        runCurrent()
        val newer = runtime.submit(TurnInput("newer"), owner) {}
        runCurrent()
        assertEquals(2, engine.emitters.size)

        val partial = partial(ContentBlock.Text(""))
        engine.emitters[0].emit(TurnEvent.TextStarted(0, partial))
        engine.emitters[0].emit(TurnEvent.TextEnded(0, "older-text", partial))
        engine.emitters[1].emit(IllegalStateException("newer-boom"))
        runCurrent()

        val afterFailure = runtime.snapshot.value
        assertEquals(older.key, afterFailure.active?.key)
        assertTrue(
            afterFailure.active!!.blocks.any {
                (it.content as? ContentBlock.Text)?.text == "older-text"
            },
        )
        assertEquals(newer.key, afterFailure.lastTerminal?.key)
        assertEquals(TurnPhase.Failed, afterFailure.lastTerminal?.phase)

        engine.emitters[0].emit(TurnResult.Completed(CompletionReason.Stop))
        runCurrent()
        assertNull(runtime.snapshot.value.active)
    }

    // ── 停止策略：caller 仅诊断，目标 owner 决定次序 ───────────────────────

    @Test
    fun homeStopStopsEngineBeforeCancelRegardlessOfCallerSurface() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handle = runtime.submit(TurnInput("q"), owner) {}
        runCurrent()

        val outcome = runtime.stop(handle.key, CommandCaller(CommandSurface.Notification))
        assertEquals(CommandOutcome.Applied, outcome)
        // Home 归属：引擎定向停止同步先跑（引擎返回 false 也仍 Applied），caller surface 不改变策略。
        assertEquals(listOf(handle.key), engine.stopTurns)
        // 捕获执行 Job 在 engine.stopTurn 调用点仍活跃：引擎 stop 先于 Job 取消。
        assertEquals(listOf(true), engine.stopTurnJobActive)
        runCurrent()
        assertEquals(TurnPhase.Cancelled, runtime.snapshot.value.lastTerminal?.phase)
    }

    @Test
    fun hostStopCancelsFirstAndStopsEngineAsynchronouslyInCapturedContext() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("host", EntrySource.Host)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handle = runtime.submit(TurnInput("q"), owner) {}
        runCurrent()

        val outcome = runtime.stop(handle.key, CommandCaller(CommandSurface.HomeChat))
        assertEquals(CommandOutcome.Applied, outcome)
        // Host 归属：先取消捕获 Job，再在捕获的 owner 上下文里异步转发引擎停止。
        assertTrue(engine.stopTurns.isEmpty())
        assertTrue(engine.stopTurnJobActive.isEmpty())
        runCurrent()
        assertEquals(listOf(handle.key), engine.stopTurns)
        // 引擎 stop 调用点目标 Job 已不活跃：取消先于异步引擎 stop。
        assertEquals(listOf(false), engine.stopTurnJobActive)
    }

    @Test
    fun staleStopAndRespondCannotTouchSuccessor() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        var firstCompleted = false
        engine.streamBody = { emitter, _ ->
            if (!firstCompleted) {
                firstCompleted = true
                emitter.emit(TurnResult.Completed(CompletionReason.Stop))
            } else {
                gate.await()
            }
        }
        val first = runtime.submit(TurnInput("one"), owner) {}
        advanceUntilIdle()
        val second = runtime.submit(TurnInput("two"), owner) {}
        runCurrent()

        assertEquals(
            CommandOutcome.IgnoredStaleTarget,
            runtime.stop(first.key, CommandCaller(CommandSurface.Host)),
        )
        assertEquals(
            CommandOutcome.IgnoredStaleTarget,
            runtime.respond(first.key, "req-1", true, CommandCaller(CommandSurface.Host)),
        )
        assertTrue(engine.stopTurns.isEmpty())
        assertEquals(second.key, runtime.snapshot.value.active?.key)

        assertEquals(
            CommandOutcome.Applied,
            runtime.stop(second.key, CommandCaller(CommandSurface.Notification)),
        )
        assertEquals(listOf(second.key), engine.stopTurns)
    }

    @Test
    fun stopTurnForwardsToEngineWhichOwnsStaleTokenMatching() = runTest {
        val engine = FakeEngine()
        val executor = ConversationExecutor(
            engine,
            RuntimeFactSink { },
            ConversationPermissionObserver(RuntimeFactSink { }),
        )
        val live = turnKey("live", 5L)
        engine.stopTurnResult = true
        assertTrue(executor.stopTurn(live))
        engine.stopTurnResult = false
        val stale = turnKey("stale", 99L)
        assertFalse(executor.stopTurn(stale))
        assertEquals(listOf(live, stale), engine.stopTurns)
    }

    // ── owner 寿命与应用作用域 ──────────────────────────────────────────────

    @Test
    fun ownerEndedHonoursOriginalTriggerOrderAndNeverResets() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val home = owner("home", EntrySource.HomeChat)
        runtime.submit(TurnInput("q"), home) {}
        runCurrent()
        runtime.ownerEnded(home, StopTrigger.OwnerCleared)
        runCurrent()
        // OwnerCleared 只取消：不转发引擎停止，也不自动 reset。
        assertTrue(engine.stopTurns.isEmpty())
        assertEquals(TurnPhase.Cancelled, runtime.snapshot.value.lastTerminal?.phase)

        val host = owner("host", EntrySource.Host)
        val hostHandle = runtime.submit(TurnInput("q"), host) {}
        runCurrent()
        runtime.ownerEnded(host, StopTrigger.HostTaskCancelled)
        runCurrent()
        // HostTaskCancelled：先取消，再异步转发引擎定向停止。
        assertEquals(listOf(hostHandle.key), engine.stopTurns)
    }

    @Test
    fun ownerEndedBinderDiedSchedulesEngineStopAndSessionResetDoesNot() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val host = owner("host", EntrySource.Host)
        val handle = runtime.submit(TurnInput("q"), host) {}
        runCurrent()
        runtime.ownerEnded(host, StopTrigger.BinderDied)
        runCurrent()
        assertEquals(listOf(handle.key), engine.stopTurns)
        // BinderDied 同 Host：引擎 stop 调用点目标 Job 已不活跃。
        assertEquals(listOf(false), engine.stopTurnJobActive)

        val resetOwner = owner("reset", EntrySource.HomeChat)
        val resetHandle = runtime.submit(TurnInput("q"), resetOwner) {}
        runCurrent()
        runtime.ownerEnded(resetOwner, StopTrigger.SessionReset)
        runCurrent()
        // SessionReset 只取消；cancel/join/reset 次序仍由原 reset 操作拥有。
        assertEquals(listOf(handle.key), engine.stopTurns)
        assertEquals(TurnPhase.Cancelled, runtime.snapshot.value.lastTerminal?.phase)
        assertNotNull(resetHandle.key)
    }

    @Test
    fun appScopeCancellationDoesNotChangeOwnerExecutionLifetime() = runTest {
        val engine = FakeEngine()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runtime = ConversationRuntime(appScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val gate = CompletableDeferred<Unit>()
        engine.streamBody = { emitter, _ ->
            gate.await()
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handle = runtime.submit(TurnInput("q"), owner) {}
        runCurrent()

        appScope.cancel()
        runCurrent()
        assertTrue(owner.parentJob.isActive)

        gate.complete(Unit)
        runCurrent()
        assertEquals(TurnPhase.Completed, runtime.snapshot.value.lastTerminal?.phase)
        runtime.await(handle)
    }

    // ── 失败交付与完成回执 ─────────────────────────────────────────────────

    @Test
    fun turnWithAlreadyCancelledParentSettlesWithoutRunningEngineBody() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val deadParent = Job(parent = backgroundScope.coroutineContext[Job]).apply { cancel() }
        val owner = ExecutionOwner(
            id = "dead",
            source = EntrySource.HomeChat,
            parentJob = deadParent,
            executionContext = StandardTestDispatcher(testScheduler),
        )
        val handle = runtime.submit(TurnInput("q"), owner) {}
        advanceUntilIdle()

        assertEquals(0, engine.streamCalls)
        assertEquals(TurnPhase.Cancelled, runtime.snapshot.value.lastTerminal?.phase)
        val thrown = runCatching { runtime.await(handle) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun bodyFailureAndOutputFailureReachAwaitAsSameThrowable() = runTest {
        val bodyEngine = FakeEngine()
        val bodyRuntime = ConversationRuntime(backgroundScope, bodyEngine)
        val bodyOwner = owner("home", EntrySource.HomeChat)
        val bodyError = IllegalStateException("body-boom")
        bodyEngine.streamBody = { emitter, _ ->
            emitter.emit(bodyError)
            throw bodyError
        }
        val bodyHandle = bodyRuntime.submit(TurnInput("q"), bodyOwner) {}
        advanceUntilIdle()
        assertSame(bodyError, runCatching { bodyRuntime.await(bodyHandle) }.exceptionOrNull())
        assertEquals(TurnPhase.Failed, bodyRuntime.snapshot.value.lastTerminal?.phase)

        val outputEngine = FakeEngine()
        val outputRuntime = ConversationRuntime(backgroundScope, outputEngine)
        val outputOwner = owner("home", EntrySource.HomeChat)
        val outputError = RuntimeException("output-boom")
        outputEngine.streamBody = { _, emit -> emit(LlmStreamEvent.TextDelta("a", "a")) }
        val outputHandle = outputRuntime.submit(TurnInput("q"), outputOwner) { throw outputError }
        advanceUntilIdle()
        assertSame(outputError, runCatching { outputRuntime.await(outputHandle) }.exceptionOrNull())
        assertEquals(TurnPhase.Failed, outputRuntime.snapshot.value.lastTerminal?.phase)
    }

    @Test
    fun normalFlowEndWithoutRawResultSettlesObservationOnlyAndAwaitSucceeds() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        engine.streamBody = { _, _ -> Unit }
        val handle = runtime.submit(TurnInput("q"), owner) {}
        advanceUntilIdle()

        // 只补结算快照，不把观察性 missing-result 交给调用方。
        assertNotNull(runtime.snapshot.value.lastTerminal)
        runtime.await(handle)
    }

    @Test
    fun repeatedRawTerminalsProduceSingleTerminalFact() = runTest {
        val engine = FakeEngine()
        val facts = mutableListOf<RuntimeFact>()
        val sink = RuntimeFactSink { facts += it }
        val executor = ConversationExecutor(engine, sink, ConversationPermissionObserver(sink))
        engine.streamBody = { emitter, _ ->
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
            emitter.emit(TurnResult.Failed(LLMError(LLMErrorCode.Transport, "late")))
            emitter.emit(CancellationException("late cancel"))
            emitter.emitCancellationRequest(Reason("STOP_REQUEST", "EXECUTOR"))
        }
        val owner = owner("home", EntrySource.HomeChat)
        val handle = executor.submit(TurnInput("q"), owner) {}
        advanceUntilIdle()

        assertEquals(1, facts.count { it is RuntimeFact.StreamResult })
        assertEquals(0, facts.count { it is RuntimeFact.ExecutionFailed })
        assertEquals(0, facts.count { it is RuntimeFact.CancellationRequested })
        executor.await(handle)
    }

    @Test
    fun observationSinkFailuresDontChangeExecutionDelivery() = runTest {
        val engine = FakeEngine()
        val throwingSink = RuntimeFactSink { throw IllegalStateException("sink-boom") }
        val executor = ConversationExecutor(
            engine,
            throwingSink,
            ConversationPermissionObserver(throwingSink),
        )
        val owner = owner("home", EntrySource.HomeChat)
        val received = mutableListOf<LlmStreamEvent>()
        engine.streamBody = { emitter, emit ->
            emit(LlmStreamEvent.TextDelta("a", "a"))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handle = executor.submit(TurnInput("q"), owner) { received += it }
        advanceUntilIdle()

        assertEquals(1, received.size)
        executor.await(handle)
    }

    @Test
    fun completedBeforeAwaitDeliversSameThrowableAndKeepsOwnerParentAlive() = runTest {
        val engine = FakeEngine()
        val runtime = ConversationRuntime(backgroundScope, engine)
        val owner = owner("home", EntrySource.HomeChat)
        val error = IllegalStateException("finished-before-await")
        engine.streamBody = { emitter, _ ->
            emitter.emit(error)
            throw error
        }
        val handle = runtime.submit(TurnInput("q"), owner) {}
        advanceUntilIdle()

        assertSame(error, runCatching { runtime.await(handle) }.exceptionOrNull())
        // 失败不再向 owner parent 传播：原入口包装器不会被连带取消。
        assertTrue(owner.parentJob.isActive)
        assertFalse(owner.parentJob.isCancelled)
    }

    @Test
    fun unconfinedAwaitResumesOnlyAfterBookkeepingAndPermissionCleanup() = runTest {
        val engine = FakeEngine()
        val facts = mutableListOf<RuntimeFact>()
        val sink = RuntimeFactSink { facts += it }
        val permissions = ConversationPermissionObserver(sink)
        val executor = ConversationExecutor(engine, sink, permissions)
        val owner = owner("home", EntrySource.HomeChat)
        var completedWithBindingCleanup = false
        var cancelAfterAwait: Boolean? = null
        var respondAfterAwait: CommandOutcome? = null
        var backgroundResponderCalls = 0
        permissions.registerBackgroundResponder { _, _ ->
            backgroundResponderCalls++
            true
        }
        engine.streamBody = { emitter, _ ->
            // 制造一个仍在观察绑定中的请求，验证 await 恢复时绑定已被清理：
            // 若绑定未清，respond 会命中已注册的后台等待器并返回 Applied。
            val auth = coroutineContext[AuthorizationContext]
            auth!!.sink.accept(
                RuntimeFact.PermissionReported(
                    observation("req-clean", auth.turn, PermissionPhase.Requested),
                ),
            )
            auth.sink.accept(
                RuntimeFact.PermissionReported(
                    observation(
                        "req-clean",
                        auth.turn,
                        PermissionPhase.Processing,
                        channel = TOOL_CONFIRM_CHANNEL_BACKGROUND,
                    ),
                ),
            )
            completedWithBindingCleanup = true
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val handle = executor.submit(TurnInput("q"), owner) {}
        val waiter = launch(Dispatchers.Unconfined) {
            executor.await(handle)
            // Unconfined：await 返回即在本线程继续，此刻 bookkeeping/权限清理必须已完成。
            cancelAfterAwait = executor.cancel(handle.key)
            respondAfterAwait = permissions.respond(handle.key, "req-clean", true)
        }
        advanceUntilIdle()
        waiter.join()

        assertTrue(completedWithBindingCleanup)
        assertEquals(false, cancelAfterAwait)
        assertEquals(CommandOutcome.IgnoredStaleTarget, respondAfterAwait)
        assertEquals(0, backgroundResponderCalls)
    }

    @Test
    fun unconfinedAwaitOnOriginalFailureResumesOnlyAfterBookkeepingAndPermissionCleanup() = runTest {
        val engine = FakeEngine()
        val facts = mutableListOf<RuntimeFact>()
        val sink = RuntimeFactSink { facts += it }
        val permissions = ConversationPermissionObserver(sink)
        val executor = ConversationExecutor(engine, sink, permissions)
        val owner = owner("home", EntrySource.HomeChat)
        val originalFailure = IllegalStateException("failure-before-await")
        var waiterObservedFailure = false
        var cancelAfterFailure: Boolean? = null
        var respondAfterFailure: CommandOutcome? = null
        var backgroundResponderCalls = 0
        permissions.registerBackgroundResponder { _, _ ->
            backgroundResponderCalls++
            true
        }
        engine.streamBody = { emitter, _ ->
            // 失败路径同样必须先清理：制造观察绑定，验证 await 抛出时绑定已解除。
            val auth = coroutineContext[AuthorizationContext]
            auth!!.sink.accept(
                RuntimeFact.PermissionReported(
                    observation("req-fail", auth.turn, PermissionPhase.Requested),
                ),
            )
            auth.sink.accept(
                RuntimeFact.PermissionReported(
                    observation(
                        "req-fail",
                        auth.turn,
                        PermissionPhase.Processing,
                        channel = TOOL_CONFIRM_CHANNEL_BACKGROUND,
                    ),
                ),
            )
            emitter.emit(originalFailure)
            throw originalFailure
        }
        val handle = executor.submit(TurnInput("q"), owner) {}
        val waiter = launch(Dispatchers.Unconfined) {
            val delivered = runCatching { executor.await(handle) }.exceptionOrNull()
            waiterObservedFailure = delivered === originalFailure
            // Unconfined：await 抛出即在本线程继续，此刻 bookkeeping/权限清理必须已完成。
            cancelAfterFailure = executor.cancel(handle.key)
            respondAfterFailure = permissions.respond(handle.key, "req-fail", true)
        }
        advanceUntilIdle()
        waiter.join()

        assertTrue(waiterObservedFailure)
        assertEquals(false, cancelAfterFailure)
        assertEquals(CommandOutcome.IgnoredStaleTarget, respondAfterFailure)
        assertEquals(0, backgroundResponderCalls)
    }

    // ── 会话操作：仅显式命令、原样回执与失败 ────────────────────────────────

    @Test
    fun operationsRunOnlyOnExplicitCommandAndCarryCapturedSnapshot() = runTest {
        val engine = FakeEngine()
        val snapshot = SessionSnapshot("okia-1", null, 1, emptyList())
        val calls = mutableListOf<ConversationOperation>()
        val runtime = ConversationRuntime(
            scope = backgroundScope,
            engine = engine,
            operationBackend = object : ConversationOperationBackend {
                override suspend fun execute(operation: ConversationOperation): OperationReceipt {
                    calls += operation
                    return OperationReceipt("room-9")
                }
            },
        )
        // 构造与空闲观察不触发任何操作/建档。
        engine.conversation.value = Conversation("okia-1", null, emptyList())
        runCurrent()
        assertTrue(calls.isEmpty())

        val outcome = runtime.operate(
            ConversationOperation.Restore("room-9", snapshot),
            CommandCaller(CommandSurface.HomeChat),
        )
        assertEquals(OperationOutcome.Succeeded("room-9"), outcome)
        val restore = calls.single() as ConversationOperation.Restore
        assertEquals("room-9", restore.persistedId)
        assertSame(snapshot, restore.snapshot)
        // 持久身份来自后端回执，不从 OKIA 会话 id 推断。
        assertEquals("room-9", runtime.snapshot.value.session.persistedId)
        assertEquals(OperationPhase.Succeeded, runtime.snapshot.value.session.phase)
    }

    @Test
    fun operationFailureIsRecordedAndRethrownUnchanged() = runTest {
        val engine = FakeEngine()
        val boom = IllegalStateException("db down")
        val runtime = ConversationRuntime(
            scope = backgroundScope,
            engine = engine,
            operationBackend = object : ConversationOperationBackend {
                override suspend fun execute(operation: ConversationOperation): OperationReceipt = throw boom
            },
        )
        val thrown = runCatching {
            runtime.operate(ConversationOperation.Reset, CommandCaller(CommandSurface.Host))
        }.exceptionOrNull()
        assertSame(boom, thrown)
        val session = runtime.snapshot.value.session
        assertEquals(OperationKind.Reset, session.operation)
        assertEquals(OperationPhase.Failed, session.phase)
        assertEquals("IllegalStateException", session.reason?.code)
    }

    @Test
    fun missingOperationBackendFailsObservablyWithoutFakingSuccess() = runTest {
        val runtime = ConversationRuntime(backgroundScope, FakeEngine())
        val outcome = runtime.operate(
            ConversationOperation.Create("hello"),
            CommandCaller(CommandSurface.HomeChat),
        )
        assertEquals(
            OperationOutcome.Failed(Reason("OPERATION_BACKEND_MISSING", "RUNTIME", null)),
            outcome,
        )
        val session = runtime.snapshot.value.session
        assertEquals(OperationKind.Create, session.operation)
        assertEquals(OperationPhase.Failed, session.phase)
        assertEquals("OPERATION_BACKEND_MISSING", session.reason?.code)
    }
}
