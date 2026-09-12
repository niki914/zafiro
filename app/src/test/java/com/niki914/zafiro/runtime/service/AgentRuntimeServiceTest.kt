package com.niki914.zafiro.runtime.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.app.AppConversationRuntime
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.runtime.BoundFactEmitter
import com.niki914.zafiro.chat.runtime.ConversationEngine
import com.niki914.zafiro.chat.runtime.ConversationOperation
import com.niki914.zafiro.chat.runtime.ConversationOperationBackend
import com.niki914.zafiro.chat.runtime.ConversationRuntime
import com.niki914.zafiro.chat.runtime.OperationReceipt
import com.niki914.zafiro.chat.runtime.RuntimeFactSink
import com.niki914.zafiro.chat.runtime.TurnKey
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import com.niki914.zafiro.app.R as AppR

/**
 * Group11 / T-27：真实 [AgentRuntimeService] 接入路径（AC1/AC3）。
 *
 * 经 Robolectric 构建真实 Service + 真实 Binder stub + 真实回调，注入真实
 * [ConversationRuntime]（fake [ConversationEngine]）。断言帧顺序/错误本地化、
 * 忙时语义、cancel/死亡/destroy/reset 的取消与引擎停止顺序；[TurnStopHandoff]
 * 的竞争次序用窄反射直接验证（无其它接缝）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentRuntimeServiceTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private lateinit var context: Context
    private lateinit var engine: ScriptedEngine
    private lateinit var backend: RecordingBackend
    private lateinit var runtimeScope: CoroutineScope
    private lateinit var controller: ServiceController<AgentRuntimeService>
    private lateinit var service: AgentRuntimeService
    private lateinit var binder: IAgentRuntimeService
    private var destroyed = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // validateCaller：调用 UID 映射到本包，才能经 onBind 取得真实 stub。
        shadowOf(context.packageManager).setPackagesForUid(Process.myUid(), context.packageName)
        ShadowBinder.setCallingUid(Process.myUid())

        engine = ScriptedEngine()
        backend = RecordingBackend(engine)
        runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AppConversationRuntime.install(ConversationRuntime(runtimeScope, engine, backend))

        controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        service = controller.get()
        binder = service.onBind(Intent()) as IAgentRuntimeService
        destroyed = false
    }

    @After
    fun tearDown() {
        if (!destroyed) {
            destroyed = true
            controller.destroy()
        }
        runtimeScope.cancel()
    }

    // ── 帧顺序与错误本地化（对比原 FullTextProjector 口径） ────────────────

    @Test
    fun submitProjectsFramesInOriginalEventOrder() {
        engine.script = {
            flowOf(
                LlmStreamEvent.RoundStarted,
                LlmStreamEvent.TextDelta("a", "a"),
                LlmStreamEvent.TextDelta("b", "ab"),
                LlmStreamEvent.Completed,
            )
        }
        val callback = RecordingCallback()

        binder.submit("hello", callback)
        awaitNoActiveTurn()

        assertEquals(
            listOf(
                RenderFrame(text = "", isFirst = true, isFinal = false),
                RenderFrame(text = "a", isFirst = false, isFinal = false),
                RenderFrame(text = "ab", isFirst = false, isFinal = false),
                RenderFrame(text = "ab", isFirst = false, isFinal = true),
            ),
            callback.frames.toList(),
        )
    }

    @Test
    fun errorWithoutMessageIsLocalizedBeforeRenderingAndEndsTurn() {
        engine.script = {
            flowOf(LlmStreamEvent.Error(message = null, code = LlmErrorCode.ConfigRequired))
        }
        val callback = RecordingCallback()

        binder.submit("hello", callback)
        awaitNoActiveTurn()

        val frame = callback.frames.single()
        assertTrue(frame.isFinal)
        assertTrue(
            frame.text.contains(
                context.getString(AppR.string.ui_home_error_config_required_title),
            ),
        )
    }

    @Test
    fun businessFailureBecomesSingleFinalErrorFrame() {
        engine.script = { throw IllegalStateException("boom") }
        val callback = RecordingCallback()

        binder.submit("hello", callback)
        awaitNoActiveTurn()

        val frame = callback.frames.single()
        assertEquals("boom", frame.text)
        assertTrue(frame.isFirst)
        assertTrue(frame.isFinal)
    }

    // ── 忙时语义：原 activeTurn CAS 拒绝，不新增门控 ──────────────────────

    @Test
    fun secondSubmitIsRejectedWhileFirstTurnIsActive() {
        engine.script = { flow { awaitCancellation() } }
        val first = RecordingCallback()
        binder.submit("first", first)
        awaitEngineStart()

        val second = RecordingCallback()
        binder.submit("second", second)

        val frame = second.frames.single()
        assertTrue(frame.text.contains("Another turn is already in progress"))
        assertTrue(frame.isFirst)
        assertTrue(frame.isFinal)
        assertTrue(first.frames.isEmpty())

        binder.cancel()
        awaitNoActiveTurn()
    }

    @Test
    fun blankAndOversizedQueriesAreRejectedWithoutStartingExecution() {
        val blank = RecordingCallback()
        binder.submit("   ", blank)
        val long = RecordingCallback()
        binder.submit("x".repeat(8193), long)

        assertEquals(1, blank.frames.size)
        assertEquals(1, long.frames.size)
        assertTrue(blank.frames.single().isFinal)
        assertTrue(long.frames.single().text.contains("exceeds maximum length"))
        assertEquals(0, engine.executions)
    }

    // ── 取消 / Binder 死亡 / destroy：原顺序与作用域停止 ───────────────────

    @Test
    fun cancelSchedulesExactlyOneScopedEngineStopForCapturedTurn() {
        engine.script = { flow { awaitCancellation() } }
        val callback = RecordingCallback()
        binder.submit("hello", callback)
        awaitEngineStart()
        val key = engine.observedKeys.single()

        binder.cancel()

        awaitCondition { engine.stopCalls.size == 1 }
        // 捕获的回合身份，而非任何“当前回合”猜测。
        val stop = engine.stopObservations.single()
        assertEquals(key, stop.turn)
        // 引擎定向停止时取消已先行：停止点观察到本次执行的 Job 已进入取消。
        assertTrue("engine stop must observe an already-cancelled execution job", stop.cancelledAtStop)
        // 已释放后的重复 cancel 不再触发第二次停止。
        binder.cancel()
        assertEquals(1, engine.stopCalls.size)
        assertEquals(1, engine.stopObservations.size)
    }

    @Test
    fun binderDeathDuringStreamSchedulesScopedStopAndFreesAdmission() {
        engine.script = {
            flow {
                emit(LlmStreamEvent.RoundStarted)
                awaitCancellation()
            }
        }
        val callback = DeadCallback()
        binder.submit("hello", callback)

        awaitCondition { engine.stopCalls.size == 1 }
        assertEquals(engine.observedKeys.single(), engine.stopCalls.single())
        assertTrue(
            "binder death must cancel the execution before the scoped engine stop",
            engine.stopObservations.single().cancelledAtStop,
        )
        awaitEngineFinish()

        // 后继安全：死亡释放准入后新回合可正常执行并收到帧。。
        engine.script = { flowOf(LlmStreamEvent.Completed) }
        val successor = RecordingCallback()
        binder.submit("next", successor)
        awaitNoActiveTurn()
        assertEquals(1, successor.frames.size)
        assertTrue(successor.frames.single().isFinal)
    }

    @Test
    fun onDestroyCancelsExecutionWithoutScopedEngineStop() {
        engine.script = { flow { awaitCancellation() } }
        val callback = RecordingCallback()
        binder.submit("hello", callback)
        awaitEngineStart()

        controller.destroy()
        destroyed = true

        awaitEngineFinish()
        // 原 onDestroy 只取消自身任务与 scope，不发出引擎定向停止。。
        assertTrue(engine.stopCalls.isEmpty())
    }

    @Test
    fun resetWaitsForWrapperCancellationThenRunsExplicitReset() {
        engine.script = { flow { awaitCancellation() } }
        val callback = RecordingCallback()
        // 只 join executor 时引擎流也会收尾；回调 Binder unlink 只在包装 finally 里发生。
        backend.wrapperFinallyProbe = { callback.unlinkCalls.get() > 0 }
        binder.submit("hello", callback)
        awaitEngineStart()

        binder.resetConversation()

        val operation = runBlocking { withTimeout(5_000) { backend.operations.receive() } }
        assertEquals(ConversationOperation.Reset, operation)
        // cancelAndJoin 已完成：操作时引擎流已收尾（原 reset 先 join 后 reset）。
        assertTrue(backend.resetSawFlowFinished == true)
        // 且等待的是包装协程而非仅 executor：包装 finally 已执行 unlinkToDeath。
        assertTrue(backend.resetSawWrapperFinally == true)
    }

    @Test
    fun resetWithoutActiveTurnStillRunsExplicitReset() {
        binder.resetConversation()

        val operation = runBlocking { withTimeout(5_000) { backend.operations.receive() } }
        assertEquals(ConversationOperation.Reset, operation)
        assertFalse(backend.resetSawFlowFinished == true)
    }

    // ── 单回合停止交接：两种到达次序都恰好调度一次 ─────────────────────────

    @Test
    fun turnStopHandoffSchedulesExactlyOnceForBothArrivalOrders() {
        // 取消先到：只记录请求，身份发布后才恰好调度一次。
        val cancelFirstScheduled = mutableListOf<TurnKey>()
        val cancelFirst = newTurnStopHandoff { cancelFirstScheduled += it }
        val key = TurnKey("conversation", "turn-1", 1L)

        requestStop(cancelFirst)
        assertTrue(cancelFirstScheduled.isEmpty())
        publishKey(cancelFirst, key)
        assertEquals(listOf(key), cancelFirstScheduled)
        requestStop(cancelFirst)
        assertEquals(listOf(key), cancelFirstScheduled)

        // 身份先到：仅发布不触发调度，取消请求到达后才恰好调度一次。
        val keyFirstScheduled = mutableListOf<TurnKey>()
        val keyFirst = newTurnStopHandoff { keyFirstScheduled += it }
        val laterKey = TurnKey("conversation", "turn-2", 2L)

        publishKey(keyFirst, laterKey)
        assertTrue(keyFirstScheduled.isEmpty())
        requestStop(keyFirst)
        assertEquals(listOf(laterKey), keyFirstScheduled)
        requestStop(keyFirst)
        assertEquals(listOf(laterKey), keyFirstScheduled)
    }

    @Test
    fun turnStopHandoffSchedulesExactlyOnceUnderSimultaneousArrivals() {
        val iterations = 200
        repeat(iterations) { index ->
            val scheduled = Collections.synchronizedList(mutableListOf<TurnKey>())
            val handoff = newTurnStopHandoff { scheduled += it }
            val key = TurnKey("conversation", "turn-$index", index.toLong())
            val begin = CountDownLatch(1)
            val stopThread = Thread {
                begin.await()
                requestStop(handoff)
            }
            val publishThread = Thread {
                begin.await()
                publishKey(handoff, key)
            }
            stopThread.start()
            publishThread.start()
            begin.countDown()
            stopThread.join()
            publishThread.join()
            assertEquals("iteration $index", listOf(key), scheduled.toList())
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun awaitNoActiveTurn() = runBlocking {
        withTimeout(5_000) {
            while (activeTurnReference() != null) delay(10)
        }
    }

    private fun awaitEngineStart() = runBlocking {
        withTimeout(5_000) { engine.startSignals.receive() }
    }

    private fun awaitEngineFinish() = runBlocking {
        withTimeout(5_000) { engine.finishSignals.receive() }
    }

    private fun awaitCondition(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    /** 窄反射：无公开接缝地读取 Service 的回合准入状态，仅用于同步等待。 */
    private fun activeTurnReference(): Any? {
        val field = AgentRuntimeService::class.java.getDeclaredField("activeTurn")
        field.isAccessible = true
        return (field.get(service) as AtomicReference<*>).get()
    }

    /** 窄反射：直接验证私有 TurnStopHandoff 的竞争次序（不被任何其它路径替代）。 */
    private fun newTurnStopHandoff(schedule: (TurnKey) -> Unit): Any {
        val type = Class.forName(
            "com.niki914.zafiro.runtime.service.AgentRuntimeService\$TurnStopHandoff",
        )
        val constructor = type.getDeclaredConstructor(Function1::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(schedule)
    }

    private fun requestStop(handoff: Any) {
        handoff.javaClass.getDeclaredMethod("requestStop")
            .apply { isAccessible = true }
            .invoke(handoff)
    }

    private fun publishKey(handoff: Any, key: TurnKey) {
        handoff.javaClass.getDeclaredMethod("publishKey", TurnKey::class.java)
            .apply { isAccessible = true }
            .invoke(handoff, key)
    }
}

private class RecordingCallback : IRenderFrameCallback.Stub() {
    val frames = Collections.synchronizedList(mutableListOf<RenderFrame>())
    val linkCalls = AtomicInteger()
    val unlinkCalls = AtomicInteger()

    override fun onFrame(frame: RenderFrame?) {
        frame?.let { frames += it }
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient?, flags: Int) {
        linkCalls.incrementAndGet()
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient?, flags: Int): Boolean {
        unlinkCalls.incrementAndGet()
        return true
    }
}

/** 首帧回传即模拟宿主进程死亡。 */
private class DeadCallback : IRenderFrameCallback.Stub() {
    val frames = Collections.synchronizedList(mutableListOf<RenderFrame>())

    override fun onFrame(frame: RenderFrame?) {
        frame?.let { frames += it }
        throw DeadObjectException()
    }
}

/** 记录 reset 操作及其发生时的引擎/包装协程收尾状态。 */
private class RecordingBackend(
    private val engine: ScriptedEngine,
) : ConversationOperationBackend {
    val operations = Channel<ConversationOperation>(Channel.UNLIMITED)

    @Volatile
    var resetSawFlowFinished: Boolean? = null

    @Volatile
    var resetSawWrapperFinally: Boolean? = null

    /** 由用例注入：读取包装 finally 专属的可观察事实（如回调 Binder unlink）。 */
    @Volatile
    var wrapperFinallyProbe: (() -> Boolean)? = null

    override suspend fun execute(operation: ConversationOperation): OperationReceipt {
        if (operation == ConversationOperation.Reset) {
            resetSawFlowFinished = engine.sawFlowFinished
            resetSawWrapperFinally = wrapperFinallyProbe?.invoke() ?: false
        }
        operations.send(operation)
        return OperationReceipt(persistedId = null)
    }
}

/** 引擎定向停止时捕获的 Job 状态快照（验证取消先于停止）。 */
private data class StopObservation(
    val turn: TurnKey,
    val cancelledAtStop: Boolean,
)

private class ScriptedEngine : ConversationEngine {
    override val conversation: StateFlow<Conversation?> = MutableStateFlow(null)

    val observedKeys = Collections.synchronizedList(mutableListOf<TurnKey>())
    val stopCalls = Collections.synchronizedList(mutableListOf<TurnKey>())
    val stopObservations = Collections.synchronizedList(mutableListOf<StopObservation>())
    val startSignals = Channel<Unit>(Channel.UNLIMITED)
    val finishSignals = Channel<Unit>(Channel.UNLIMITED)

    private val executionJobs = ConcurrentHashMap<TurnKey, Job>()

    @Volatile
    var sawFlowFinished = false

    private val lock = Any()
    private var count = 0

    var script: (Int) -> Flow<LlmStreamEvent> = { flow { awaitCancellation() } }

    val executions: Int get() = synchronized(lock) { count }

    override fun stream(
        query: String,
        images: List<ContentBlock.Image>,
        observer: RuntimeFactSink,
    ): Flow<LlmStreamEvent> {
        val index = synchronized(lock) { count++ }
        return flow {
            val key = (observer as BoundFactEmitter).scope.key
            // 收集时捕获本次执行的 Job：引擎停止时可比对它是否已取消。
            coroutineContext[Job]?.let { executionJobs[key] = it }
            observedKeys += key
            startSignals.send(Unit)
            try {
                script(index).collect { emit(it) }
            } finally {
                sawFlowFinished = true
                finishSignals.send(Unit)
            }
        }
    }

    override suspend fun stopTurn(turn: TurnKey): Boolean {
        stopObservations += StopObservation(
            turn = turn,
            cancelledAtStop = executionJobs[turn]?.isCancelled == true,
        )
        stopCalls += turn
        return true
    }
}
