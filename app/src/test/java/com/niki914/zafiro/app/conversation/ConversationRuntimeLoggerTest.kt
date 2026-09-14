package com.niki914.zafiro.app.conversation

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import com.niki914.zafiro.chat.runtime.BoundFactEmitter
import com.niki914.zafiro.chat.runtime.ConversationEngine
import com.niki914.zafiro.chat.runtime.ConversationRuntime
import com.niki914.zafiro.chat.runtime.EntrySource
import com.niki914.zafiro.chat.runtime.ExecutionOwner
import com.niki914.zafiro.chat.runtime.RuntimeFactSink
import com.niki914.zafiro.chat.runtime.TurnHandle
import com.niki914.zafiro.chat.runtime.TurnInput
import com.niki914.zafiro.chat.runtime.TurnKey
import com.niki914.zafiro.chat.runtime.TurnPhase
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Group11 / T-26：真实 [ConversationRuntimeLogger] 消费真实 runtime 发布的事实。
 *
 * 每个用例都把事实经真实 executor → observer → reducer 路径发出（engine 只做
 * LLMController 的原始 emit 行为），断言 logger 实际输出的记录，不复制 formatter。
 */
class ConversationRuntimeLoggerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        ToolPermissionCoordinator.backgroundConfirmationHandler = null
        ToolPermissionCoordinator.isUiResumed = false
    }

    // ── 默认输出：状态面 + 长度 + 原因，且不含任何正文/参数/结果/敏感原文 ──────

    @Test
    fun defaultOutputCarriesStatusMetadataAndLengthsWithoutPayloads() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sink = RecordingSink()
        ToolPermissionCoordinator.backgroundConfirmationHandler = { ToolPermissionResponse.ALLOWED }
        ToolPermissionCoordinator.isUiResumed = false

        // 每个阶段都以“logger 已观察到该瞬态”为屏障，再放行下一段事实：
        // 同步连发会被 StateFlow 合并，Streaming/Allowed/retry 等断言目标可能从未被看到。
        val retryGate = CompletableDeferred<Unit>()
        val textGate = CompletableDeferred<Unit>()
        val textEndedGate = CompletableDeferred<Unit>()
        val thinkingGate = CompletableDeferred<Unit>()
        val toolGate = CompletableDeferred<Unit>()
        val permissionGate = CompletableDeferred<Unit>()
        val terminalGate = CompletableDeferred<Unit>()
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TurnStarted(SECRET_INPUT))
            retryGate.await()
            emitter.emit(TurnEvent.RetryScheduled(1, 3, 500L, SECRET_RETRY_REASON))
            textGate.await()
            emitter.emit(TurnEvent.TextStarted(0, assistant(ContentBlock.Text(SECRET_TEXT))))
            emitter.emit(
                TurnEvent.TextDelta(0, SECRET_TEXT, assistant(ContentBlock.Text(SECRET_TEXT))),
            )
            textEndedGate.await()
            emitter.emit(
                TurnEvent.TextEnded(0, SECRET_TEXT, assistant(ContentBlock.Text(SECRET_TEXT))),
            )
            thinkingGate.await()
            val thinkingPartial = assistant(
                ContentBlock.Text(SECRET_TEXT),
                ContentBlock.Thinking(SECRET_THINKING),
            )
            emitter.emit(TurnEvent.ThinkingStarted(1, thinkingPartial))
            emitter.emit(TurnEvent.ThinkingDelta(1, SECRET_THINKING, thinkingPartial))
            toolGate.await()
            // ThinkingEnded 等工具阶段放行后再发：否则同一批事实里 Ended 会覆盖
            // Streaming，屏障等到的是 Ended 快照。
            emitter.emit(TurnEvent.ThinkingEnded(1, SECRET_THINKING, thinkingPartial))
            val tool = ContentBlock.ToolCall("call-1", SECRET_TOOL, SECRET_ARGS)
            emitter.emit(
                TurnEvent.ToolCallStarted(2, thinkingPartial, callId = "call-1", toolName = SECRET_TOOL),
            )
            emitter.emit(TurnEvent.ToolCallDelta(2, SECRET_ARGS, thinkingPartial))
            emitter.emit(TurnEvent.ToolCallReady(2, tool, thinkingPartial))
            emitter.emit(
                TurnEvent.ToolSucceeded(
                    2, tool, ToolCallOutcome.Success(SECRET_RESULT), thinkingPartial,
                ),
            )
            permissionGate.await()
            ToolPermissionCoordinator.confirm(
                ToolPermissionRequest(
                    id = "req-1",
                    toolName = SECRET_TOOL,
                    command = SECRET_ARGS,
                    matchedRuleName = "rule",
                ),
            )
            terminalGate.await()
            emitter.emit(TurnEvent.TurnCompleted(assistant(tool)))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)

        val handle = runtime.submitWith(dispatcher)

        awaitRecords(sink, "v=", "session{", "phase=", "reason=", "blocks=[", "tools=[", "perms=[", "blockers=[")
        retryGate.complete(Unit)
        awaitRecords(sink, "?code(len=${SECRET_RETRY_REASON.length})@OKIA", "RetryBackoff")
        textGate.complete(Unit)
        awaitRecords(sink, "Text/Streaming", "len=${SECRET_TEXT.length}")
        textEndedGate.complete(Unit)
        awaitRecords(sink, "Text/Ended")
        thinkingGate.complete(Unit)
        awaitRecords(sink, "Thinking/Streaming", "len=${SECRET_THINKING.length}")
        toolGate.complete(Unit)
        awaitRecords(
            sink,
            "nameLen=${SECRET_TOOL.length}",
            "argsLen=${SECRET_ARGS.length}",
            "outcome=Success",
        )
        permissionGate.complete(Unit)
        awaitRecords(sink, "kind=ToolConfirmation", "channel=background", "phase=Allowed", "attempts=")
        terminalGate.complete(Unit)
        awaitRecords(sink, "last{", "phase=Completed")
        runtime.await(handle)

        val combined = sink.combined()
        // 默认不输出任何正文/思考/工具名/参数/结果/权限 target 原文。。
        listOf(
            SECRET_INPUT, SECRET_TEXT, SECRET_THINKING, SECRET_TOOL, SECRET_ARGS, SECRET_RESULT,
            SECRET_RETRY_REASON,
        ).forEach { secret ->
            assertFalse("default output leaked $secret", combined.contains(secret))
        }
        // 默认无调试摘要。
        assertFalse(combined.contains(" debug="))

        scope.cancel()
    }

    @Test
    fun defaultOutputOmitsExceptionClassAndDetail() {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sink = RecordingSink()
        val engine = ScriptedEngine {
            throw IllegalStateException(SECRET_EXCEPTION)
        }
        val runtime = ConversationRuntime(scope, engine)
        ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)

        runtime.submitWith(dispatcher)

        val combined = sink.combined()
        assertTrue(combined.contains("EXECUTION_FAILED@EXECUTOR"))
        assertFalse(combined.contains(SECRET_EXCEPTION))
        assertFalse(combined.contains("IllegalStateException"))

        scope.cancel()
    }

    // ── 瘦身：连续量不重复成行 + 门控关闭时不构造记录 ──────────────────────────

    @Test
    fun lengthOnlyGrowthIsNotRepeatedInRecords() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sink = RecordingSink()
        val finalText = "x".repeat(41)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TurnStarted("q"))
            emitter.emit(TurnEvent.TextStarted(0, assistant(ContentBlock.Text("x"))))
            repeat(40) { i ->
                val text = "x".repeat(i + 2)
                emitter.emit(TurnEvent.TextDelta(0, text, assistant(ContentBlock.Text(text))))
            }
            emitter.emit(TurnEvent.TextEnded(0, finalText, assistant(ContentBlock.Text(finalText))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)

        val handle = runtime.submitWith(dispatcher)
        runtime.await(handle)
        advanceUntilIdle()

        // 40 次长度增长与 Started 同形状，不得各成一行；Ended 是形状变化，必须成行。
        val streaming = sink.records.filter { it.contains("Text/Streaming") }
        assertTrue(
            "length-only growth must not repeat, got ${sink.records.size} records",
            streaming.size <= 1,
        )
        assertEquals(1, sink.records.count { it.contains("Text/Ended") })
        assertTrue(sink.records.any { it.contains("len=${finalText.length}") })

        scope.cancel()
    }

    @Test
    fun disabledSinkSkipsRecordConstructionEntirely() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sink = RecordingSink(enabled = false)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TextEnded(0, "a", assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)

        val handle = runtime.submitWith(dispatcher)
        runtime.await(handle)
        advanceUntilIdle()

        // 关闭时连快照都不格式化：sink 一次也不被调。
        assertTrue("disabled sink received ${sink.records.size} records", sink.records.isEmpty())
        assertEquals(TurnPhase.Completed, runtime.snapshot.value.lastTerminal?.phase)

        scope.cancel()
    }

    // ── 调试摘要：敏感片段整段丢弃 + 120 字符上限 ────────────────────────────

    @Test
    fun debugSummaryKeepsBoundedBenignTextButDropsSecrets() {
        // 良性短文本：保留。。
        assertTrue(driveText("hello world", debugSummary = true).contains(" debug=hello world"))
        // 超过 120 字符：截断到 120。。
        val long = "x".repeat(200)
        val longLog = driveText(long, debugSummary = true)
        assertTrue(longLog.contains("debug=" + "x".repeat(120)))
        assertFalse(longLog.contains("x".repeat(121)))
        // 各类凭据片段：整段丢弃，不做部分替换。。
        listOf(
            "sk-abcdefghijklmnop",
            "eyJhbGciOiJIUzI1NiIsInR5cCI6",
            "Bearer abcdefgh1234",
            """{"api_key":"abc def"}""",
            "password=hunter2",
        ).forEach { secret ->
            assertFalse(
                "debug summary leaked $secret",
                driveText(secret, debugSummary = true).contains(" debug="),
            )
        }
        // 默认关闭：良性文本也不追加摘要。。
        assertFalse(driveText("hello world", debugSummary = false).contains(" debug="))
    }

    // ── 晚订阅 / 慢消费 / 取消订阅 ──────────────────────────────────────────

    @Test
    fun lateSubscriberImmediatelyObservesLatestCompleteState() {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TurnStarted("q"))
            emitter.emit(TurnEvent.TextEnded(0, "done", assistant(ContentBlock.Text("done"))))
            emitter.emit(TurnEvent.TurnCompleted(assistant(ContentBlock.Text("done"))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        runtime.submitWith(dispatcher)

        val version = runtime.snapshot.value.version
        val sink = RecordingSink()
        val job = ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)

        val combined = sink.combined()
        assertTrue(sink.records.isNotEmpty())
        assertTrue(sink.records.first().startsWith("v=$version "))
        assertTrue(combined.contains("last{"))
        assertTrue(combined.contains("phase=Completed"))

        job.cancel()
        scope.cancel()
    }

    @Test
    fun slowSubscriberConflatesVersionsButEndsCompleteWithoutBlockingExecution() = runTest {
        val producer = UnconfinedTestDispatcher(testScheduler)
        val slow = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + producer)
        val sink = RecordingSink()
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TurnStarted("q"))
            emitter.emit(TurnEvent.TextDelta(0, "a", assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnEvent.TextEnded(0, "a", assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnEvent.TurnCompleted(assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        val job = ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = slow)

        val handle = runtime.submitWith(producer)
        runtime.await(handle)

        // 慢消费者不反压：执行已结束，且没有因日志启动额外执行。。
        assertEquals(1, engine.executions)
        assertTrue(sink.records.isEmpty())

        advanceUntilIdle()

        val version = runtime.snapshot.value.version
        val finalRecords = sink.records.filter { it.startsWith("v=$version ") }
        assertTrue(finalRecords.isNotEmpty())
        assertTrue(finalRecords.joinToString("\n").contains("phase=Completed"))
        // 允许版本跳跃，但不能逐版本全量。。
        assertTrue(sink.records.count { it.startsWith("v=") } < version)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun cancelledSubscriberCannotStartOrStopExecution() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TurnCompleted(assistant(ContentBlock.Text("done"))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        val sink = RecordingSink()
        val job = ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = dispatcher)
        val afterStart = sink.records.size
        job.cancel()

        val handle = runtime.submitWith(dispatcher)
        runtime.await(handle)

        assertEquals(1, engine.executions)
        assertEquals(TurnPhase.Completed, runtime.snapshot.value.lastTerminal?.phase)
        assertEquals(afterStart, sink.records.size)

        scope.cancel()
    }

    @Test
    fun sinkFailuresAreIsolatedFromExecution() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TextEnded(0, "a", assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnEvent.TurnCompleted(assistant(ContentBlock.Text("a"))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        val failing = FailingSink()
        val job = ConversationRuntimeLogger.start(runtime, scope, sink = failing, dispatcher = dispatcher)

        val handle = runtime.submitWith(dispatcher)
        // sink 高频抛错不得变成执行异常或取消收集。。
        runtime.await(handle)

        assertTrue(failing.calls.get() > 1)
        assertEquals(TurnPhase.Completed, runtime.snapshot.value.lastTerminal?.phase)

        job.cancel()
        scope.cancel()
    }

    // ── 长快照分片：UTF-8 字节界 + 不丢列表尾部 ─────────────────────────────

    @Test
    fun longMultiByteManyToolSnapshotIsSplitIntoCorrelatedBoundedRecords() = runTest {
        val producer = UnconfinedTestDispatcher(testScheduler)
        val slow = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + producer)
        val toolCount = 120
        val sink = RecordingSink()
        val engine = ScriptedEngine { emitter ->
            repeat(toolCount) { i ->
                val callId = "call-" + i.toString().padStart(3, '0')
                val call = ContentBlock.ToolCall(callId, TOOL_NAME, ARGUMENTS)
                val partial = assistant(call)
                emitter.emit(TurnEvent.ToolCallReady(i, call, partial))
            }
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        val job = ConversationRuntimeLogger.start(runtime, scope, sink = sink, dispatcher = slow)

        runtime.submitWith(producer)
        advanceUntilIdle()

        val version = runtime.snapshot.value.version
        val records = sink.records.filter { it.startsWith("v=$version ") }
        assertTrue("expected split records, got ${records.size}", records.size >= 2)
        records.forEach { record ->
            assertTrue(
                "record exceeds Logcat budget: ${utf8Bytes(record)} bytes",
                utf8Bytes(record) <= 3000,
            )
        }

        // 关联契约：同版本 + i/n 连续，顺序可还原。。
        val parsed = records.map { RECORD.matchEntire(it) ?: error("bad record: $it") }
        val total = parsed.first().groupValues[3].toInt()
        assertEquals(records.size, total)
        parsed.forEachIndexed { index, match ->
            assertEquals(version.toInt(), match.groupValues[1].toInt())
            assertEquals(index + 1, match.groupValues[2].toInt())
            assertEquals(total, match.groupValues[3].toInt())
        }

        // 不丢列表尾部：工具条目数完整、末条含最后一个 callId。。
        val combined = records.joinToString("")
        assertEquals(toolCount, Regex("nameLen=").findAll(combined).count())
        assertEquals(toolCount, Regex("Ready call=").findAll(combined).count())
        val lastCallId = "call-" + (toolCount - 1).toString().padStart(3, '0')
        assertTrue(combined.contains(lastCallId))

        job.cancel()
        scope.cancel()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** 单个 TextEnded 回合经真实 runtime 发出，返回 logger 实际写入的全部记录。 */
    private fun driveText(text: String, debugSummary: Boolean): String {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = ScriptedEngine { emitter ->
            emitter.emit(TurnEvent.TextEnded(0, text, assistant(ContentBlock.Text(text))))
            emitter.emit(TurnResult.Completed(CompletionReason.Stop))
        }
        val runtime = ConversationRuntime(scope, engine)
        val sink = RecordingSink()
        val job = ConversationRuntimeLogger.start(
            runtime, scope, debugSummary = debugSummary, sink = sink, dispatcher = dispatcher,
        )
        runtime.submitWith(dispatcher)
        job.cancel()
        scope.cancel()
        return sink.combined()
    }

    /** 确定性屏障：跑光当前已排队任务后，断言 logger 确实观察到了给定标记。 */
    private fun TestScope.awaitRecords(sink: RecordingSink, vararg markers: String) {
        advanceUntilIdle()
        val records = sink.records.toList()
        markers.forEach { marker ->
            assertTrue(
                "logger never recorded '$marker'; got:\n${records.joinToString("\n")}",
                records.any { it.contains(marker) },
            )
        }
    }

    private fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun assistant(vararg blocks: ContentBlock) = AssistantMessage(blocks.toList())

    private companion object {
        const val SECRET_INPUT = "SECRET_USER_INPUT"
        const val SECRET_TEXT = "SECRET_TEXT_BODY"
        const val SECRET_THINKING = "SECRET_THINKING_BODY"
        const val SECRET_TOOL = "SECRET_TOOL_NAME"
        const val SECRET_ARGS = "SECRET_ARGUMENTS_JSON"
        const val SECRET_RESULT = "SECRET_TOOL_RESULT"
        const val SECRET_RETRY_REASON = "retry-secret-reason"
        const val SECRET_EXCEPTION = "SECRET_EXCEPTION_DETAIL"
        const val TOOL_NAME = "工具名称工具名称"
        val ARGUMENTS = "参数".repeat(20)
        val RECORD = Regex("^v=(\\d+) (\\d+)/(\\d+)(.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}

/** 记录型 sink：捕获 logger 实际写入的每条记录。 */
private class RecordingSink(private val enabled: Boolean = true) : ConversationRuntimeLogSink {
    val records = Collections.synchronizedList(mutableListOf<String>())

    override fun isEnabled(): Boolean = enabled

    override fun log(message: String) {
        records += message
    }

    fun combined(): String = records.joinToString("\n")
}

/** 每条记录都抛错的 sink：验证隔离。 */
private class FailingSink : ConversationRuntimeLogSink {
    val calls = java.util.concurrent.atomic.AtomicInteger()

    override fun log(message: String) {
        calls.incrementAndGet()
        throw IllegalStateException("sink down")
    }
}

/**
 * 模仿 LLMController 的生产行为：把原始 TurnEvent/TurnResult 经 [BoundFactEmitter]
 * 送出（身份由 executor 捕获），并输出一个最小 [LlmStreamEvent] 流。
 */
private class ScriptedEngine(
    private val script: suspend (BoundFactEmitter) -> Unit,
) : ConversationEngine {
    val conversationFlow = MutableStateFlow<Conversation?>(null)

    override val conversation: StateFlow<Conversation?> get() = conversationFlow

    var executions = 0
        private set

    override fun stream(
        query: String,
        images: List<ContentBlock.Image>,
        observer: RuntimeFactSink,
    ): Flow<LlmStreamEvent> = flow {
        executions++
        script(observer as BoundFactEmitter)
        emit(LlmStreamEvent.Completed)
    }

    override suspend fun stopTurn(turn: TurnKey): Boolean = true
}

private fun ConversationRuntime.submitWith(dispatcher: CoroutineDispatcher): TurnHandle =
    submit(
        TurnInput("q"),
        ExecutionOwner("owner-1", EntrySource.HomeChat, Job(), dispatcher),
    ) { }
