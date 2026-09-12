package com.niki914.zafiro.chat.runtime

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * 执行引擎接缝（T-04）：executor 只依赖本接口，生产默认 [LlmControllerEngine]，
 * 测试注入可控替身。不越过本接口做引擎/配置/权限决定。
 */
interface ConversationEngine {
    /** 当前会话快照统一流（与持久化/日志同源，不重建内容；无会话时为 null）。 */
    val conversation: StateFlow<Conversation?>

    /** 冷流：每次 collect 发起一次执行；观察汇点身份由 executor 绑定后传入。 */
    fun stream(
        query: String,
        images: List<ContentBlock.Image>,
        observer: RuntimeFactSink,
    ): Flow<LlmStreamEvent>

    /**
     * 定向停止引擎侧回合（AC9）：[turn] 是执行身份，引擎在 send 时已将其绑定为
     * 回合 token。token 不再匹配（回合已结束/会话已切换/实例已替换）时返回 false
     * 且不触碰任何当前回合。
     */
    suspend fun stopTurn(turn: TurnKey): Boolean
}

/** 生产默认：包装 [LLMController]，不新增行为。 */
object LlmControllerEngine : ConversationEngine {
    override val conversation: StateFlow<Conversation?> get() = LLMController.currentConversation

    override fun stream(
        query: String,
        images: List<ContentBlock.Image>,
        observer: RuntimeFactSink,
    ): Flow<LlmStreamEvent> = LLMController.stream(query, images, observer)

    override suspend fun stopTurn(turn: TurnKey): Boolean = LLMController.stopTurn(turn)
}

/**
 * 唯一执行冷流收集点（T-04，AC2/AC3/AC9）。
 *
 * - 唯一收集：[submit] 是发起执行的唯一入口，返回只带身份与私有完成回执的 [TurnHandle]，
 *   不暴露 Job 所有权；停止一律走 [cancel]/[ownerEnded]。
 * - 寿命等价：执行 Job 是 [ExecutionOwner.parentJob] 的子 Job，其余上下文来自
 *   [ExecutionOwner.executionContext]（集成方的原作用域上下文），不强制迁移
 *   调度器，也不上移/延长寿命；不引入独立于 owner 的 supervisor/额外 Job。
 * - 顺序等价：兼容输出经 [output] 按原顺序同步交付，不从 snapshot 重建。
 * - 身份绑定：执行冷流包在 [ConversationPermissionObserver.contextFor] 的
 *   [AuthorizationContext] 中，工具/屏控授权按捕获的 [TurnKey] 归属。
 * - start→terminal 必达：[RuntimeFact.ExecutionStarted] 在 start 前同步发出；
 *   终态由 [RuntimeFact.StreamResult] / [RuntimeFact.ExecutionFailed] 结算，
 *   体未运行（父 Job 已取消）或体未结算时由完成回调补齐，且不重复结算。
 * - 失败交付：[await] 走 handle 私有完成回执，业务/输出非取消异常原样保留并在
 *   await 点抛出（含完成早于 await）；不再向 owner Job 传播，因此不会先取消
 *   原入口包装器，也不产生未处理的 launch 异常。取消异常按协程语义保留。
 * - 结算时机：体内只记录原始失败，不发布回执；回执在 Job 完成回调中、
 *   bookkeeping 与 [ConversationPermissionObserver.clearTurn] 清理之后才发布，
 *   await 恢复时清理已生效，不会提前唤醒原包装器 catch/finally。
 * - 正常结束不发明失败：无 raw 终态时只为 snapshot 补结算，不把该观察性
 *   “missing result” 异常交给 [await]（原流程正常结束时 await 正常返回）。
 * - 收尾必达：回合 bookkeeping 清理与 [ConversationPermissionObserver.clearTurn]
 *   挂在 Job 完成回调上，不依赖体 finally。
 *
 * attempt 归一化口径：OKIA 的 [TurnEvent.RetryScheduled.attempt] 是分层计数
 * （传输层与段首重试各自从 1 起，同一执行内可重复），不能直接作回合视图分段。
 * executor 以执行内单调序号为准：首次尝试为 1，每收到一个 RetryScheduled 事件
 * 后序号加一；该事件本身归属其失败的那次尝试，其后事件归属新尝试。
 */
class ConversationExecutor(
    private val engine: ConversationEngine,
    private val sink: RuntimeFactSink,
    private val permissions: ConversationPermissionObserver,
) {
    private val lock = Any()
    private val epochs = AtomicLong(0L)
    private val executions = LinkedHashMap<TurnKey, Execution>()

    /** 无会话实例时的稳定运行时身份；不是持久化 ID，仅保证 [TurnKey.conversationId] 非空。 */
    private val runtimeIdentity: String = "runtime-" + UUID.randomUUID()

    /**
     * 引擎会话统一流（同一来源，不复制）。Group6 在 Runtime/App 寿命内订阅并向
     * reducer 转发 [RuntimeFact.ConversationUpdated]（含 reset/close 后的 null 清空），
     * 不在每次执行内单独起观察协程。
     */
    val conversation: StateFlow<Conversation?> get() = engine.conversation

    private class Execution(
        val owner: ExecutionOwner,
        val job: Job,
        val emitter: BoundFactEmitter,
        val completion: ExecutionCompletion,
    )

    /**
     * 执行完成回执：只保存原始失败（取消/非取消）或 null，不暴露 Job/Deferred；
     * [awaitCompletion] 在调用点原样抛出失败。
     */
    private class ExecutionCompletion : TurnCompletion {
        private val failure = CompletableDeferred<Throwable?>()

        /** 体在结束前记录原始业务/输出失败；不在此结算回执。 */
        private val recorded = AtomicReference<Throwable?>(null)

        /**
         * 记录原始失败，不发布回执。发布由 Job 完成回调在 bookkeeping/权限清理之后
         * 调用 [complete]，确保 await 恢复时执行与清理均已结。
         */
        fun record(error: Throwable) {
            recorded.set(error)
        }

        /**
         * 在 Job 完成且清理之后结算（仅一次）：原记录失败优先；否则用 Job 完成原因
         * （含取消/启动前父取消）；两者皆无为 null（正常结束）。
         */
        fun complete(cause: Throwable?) {
            failure.complete(recorded.get() ?: cause)
        }

        override suspend fun awaitCompletion() {
            failure.await()?.let { throw it }
        }
    }

    /**
     * 发起一次执行。忙时/重复发送判断仍在原入口（HomeChat isGenerating、Service
     * activeTurn），executor 不新增统一拒绝；每个 owner 提交即执行。
     * [conversationId] 由集成方在 start 前提供；缺省取引擎当前会话 id，
     * 都无则用本 executor 的稳定运行时身份。
     */
    fun submit(
        input: TurnInput,
        owner: ExecutionOwner,
        output: suspend (LlmStreamEvent) -> Unit,
        conversationId: String? = null,
    ): TurnHandle {
        val key: TurnKey
        val emitter: ExecutionEmitter
        val job: Job
        val execution: Execution
        val completion = ExecutionCompletion()
        // epoch 分配与 ExecutionStarted 发布同处一个短临界区：start 事实严格按
        // epoch 递增到达 reducer 水位线，不会出现 A(epoch1) 晚于 B(epoch2) 发布
        // 而被永久拒绝。观察者重入 submit 时（synchronized 可重入）重入发生在本次
        // start 事实已交付之后，新 epoch 不会抢在本条之前。
        // 懒启动：先建 Job（不运行）→ 登记 → 发 start → 装完成回调 → start。
        // 父 Job 已取消时完成回调在安装处立即触发，此时条目已在 map 中，顺序仍为
        // start → terminal，且不会留下未清理条目。
        synchronized(lock) {
            key = TurnKey(
                conversationId = conversationId
                    ?: engine.conversation.value?.id
                    ?: runtimeIdentity,
                turnId = UUID.randomUUID().toString(),
                epoch = epochs.incrementAndGet(),
            )
            emitter = ExecutionEmitter(key, owner.source)
            job = CoroutineScope(owner.executionContext + owner.parentJob)
                .launch(start = CoroutineStart.LAZY) {
                    runExecution(key, input, output, emitter, completion)
                }
            execution = Execution(owner, job, emitter, completion)
            executions[key] = execution
            emitter.emitFact(RuntimeFact.ExecutionStarted(key, owner.source, input))
        }
        job.invokeOnCompletion { cause ->
            // 体未运行或未结算：按完成原因补终态（CAS 保证与体自身终态不重复）。
            emitter.emit(
                cause ?: IllegalStateException("execution ended without terminal result"),
            )
            try {
                synchronized(lock) {
                    if (executions[key] === execution) executions.remove(key)
                }
                permissions.clearTurn(key)
            } finally {
                // 回执发布放在 bookkeeping/权限清理之后，且用 finally 保证清理异常也不会
                // 永久搁置等待者：await(handle) 恢复时执行已终止、登记已移除、观察已清干净，
                // 不会在清理前提前唤醒原包装器 catch/finally。
                // 原记录失败优先，否则保留 Job 完成原因（取消/启动前父取消）。
                completion.complete(cause)
            }
        }
        job.start()
        return TurnHandle(key, completion)
    }

    /**
     * 定向停止（兼容钩子）：仅当 [turn] 仍是登记中的执行才取消其 Job。
     * 过期/已结束身份返回 false，绝不影响后来创建的新回合，也不触碰引擎。
     * 取消前发取消请求事实（已结算目标不伪造）。适用于 new/load/delete-current/reset
     * 等适配器的原局部取消。
     */
    fun cancel(turn: TurnKey): Boolean {
        val execution = synchronized(lock) { executions[turn] } ?: return false
        execution.emitter.emitCancellationRequest(CANCEL_REQUEST_REASON)
        execution.job.cancel()
        return true
    }

    /**
     * 统一停止命令（Group6 门面委托，AC9）：顺序只看**捕获的目标执行** -
     * [ExecutionOwner.source] 与原始入口，不看调用者 surface（Notification 调用者
     * 停 Home 回合也不改变其策略）。返回实际生效/无操作结果；过期/已结算目标
     * 不触碰新回合。
     *
     * - Home 归属（原 stopGenerating）：先 [ConversationEngine.stopTurn]（引擎侧
     *   kill-then-stop），再取消捕获 Job。引擎尚未准入（返回 false）但准备中的
     *   Job 已被取消时仍为 [CommandOutcome.Applied]。
     * - Host 归属（原 Service cancel）：先取消捕获 Job，再在**捕获的** owner
     *   执行上下文里异步 [ConversationEngine.stopTurn]，不扩大作用域/寿命。
     */
    suspend fun stop(turn: TurnKey): CommandOutcome {
        val execution = synchronized(lock) { executions[turn] } ?: return CommandOutcome.IgnoredStaleTarget
        // 已发终态的执行不再接收取消请求，也不因仍在 map 中而重启停止。
        if (execution.emitter.settled) return CommandOutcome.IgnoredStaleTarget
        execution.emitter.emitCancellationRequest(STOP_REQUEST_REASON)
        return when (execution.owner.source) {
            EntrySource.HomeChat -> {
                engine.stopTurn(turn)
                execution.job.cancel()
                CommandOutcome.Applied
            }

            EntrySource.Host -> {
                val context = execution.owner.executionContext
                execution.job.cancel()
                scheduleEngineStop(turn, context)
                CommandOutcome.Applied
            }
        }
    }

    /**
     * 定向停止（兼容钩子）：无条件转发引擎 [ConversationEngine.stopTurn]，由引擎侧
     * token 匹配提供过期安全（未登记/已清理的 TurnKey 在引擎侧不命中，不触碰新回合）。
     * 调用侧仍按原入口顺序使用（HomeChat：先本方法、后 [cancel]；Host：先 [cancel]、
     * 后异步本方法）；executor 不新增仲裁或排队。
     */
    suspend fun stopTurn(turn: TurnKey): Boolean = engine.stopTurn(turn)

    /**
     * 兼容 join（仅 [TurnKey] 可查的登记条目）：等价于对原 Job join，不暴露 Job，
     * 不带失败回执；已完成/未登记立即返回。新接线请用 [await] 的 handle 重载，
     * 以免登记清理后丢失原始失败。
     */
    suspend fun await(turn: TurnKey) {
        val job = synchronized(lock) { executions[turn]?.job } ?: return
        job.join()
    }

    /**
     * 等待原执行结束并交付原始失败：消费 [TurnHandle] 自带的完成回执，不查登记表，
     * 因此完成早于 await（条目已被清理）仍有效；非取消业务/输出异常原样抛出，
     * 取消异常保留，无原始失败（含无 raw 终态的观察性结束）正常返回。不暴露 Job/Deferred。
     */
    suspend fun await(handle: TurnHandle) = handle.completion.awaitCompletion()

    /**
     * owner 寿命结束（onCleared/Binder death/destroy/scope cancel 等价）：取消该 owner
     * 创建的全部执行，沿父 Job 取消相应传播，顺序按原触发来源：
     * - [StopTrigger.OwnerCleared] / [StopTrigger.ServiceDestroyed]：只取消（原
     *   HomeChat onCleared / Service onDestroy）。
     * - [StopTrigger.HostTaskCancelled] / [StopTrigger.BinderDied]：先取消，再在
     *   捕获的 owner 执行上下文里异步转发引擎定向停止（原 Service cancel / Binder
     *   death）；不扩大作用域或寿命。
     * - [StopTrigger.SessionReset]：只取消；cancel/join/reset 次序仍由原 reset 操作
     *   拥有，此处不自动 reset。
     *
     * 取消前先捕获 key/执行/上下文，避免完成回调清理登记条目后丢失目标；
     * 已结算目标不伪造取消请求。
     */
    fun ownerEnded(owner: ExecutionOwner, trigger: StopTrigger) {
        val targets = synchronized(lock) {
            executions.entries.filter { it.value.owner.id == owner.id }
                .map { it.key to it.value }
        }
        if (targets.isEmpty()) return
        val reason = Reason(trigger.name, ORIGIN_EXECUTOR)
        targets.forEach { (_, execution) ->
            if (!execution.emitter.settled) execution.emitter.emitCancellationRequest(reason, trigger)
        }
        val scheduleStop =
            trigger == StopTrigger.HostTaskCancelled || trigger == StopTrigger.BinderDied
        val contexts: Map<TurnKey, CoroutineContext> = if (scheduleStop) {
            targets.associate { (key, execution) -> key to execution.owner.executionContext }
        } else {
            emptyMap()
        }
        targets.forEach { (_, execution) -> execution.job.cancel() }
        contexts.forEach { (key, context) -> scheduleEngineStop(key, context) }
    }

    /** 在捕获的执行上下文里异步转发引擎定向停止；异常与原 Service 一致地吞掉。 */
    private fun scheduleEngineStop(turn: TurnKey, context: CoroutineContext) {
        CoroutineScope(context).launch {
            try {
                engine.stopTurn(turn)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun runExecution(
        key: TurnKey,
        input: TurnInput,
        output: suspend (LlmStreamEvent) -> Unit,
        emitter: ExecutionEmitter,
        completion: ExecutionCompletion,
    ) {
        try {
            val auth = permissions.contextFor(key)
            withContext(auth) {
                engine.stream(input.query, input.images, emitter).collect { output(it) }
            }
            // 无 raw 终态：只为 snapshot 补结算（保证回合必被结算），
            // 不写入完成回执——原流程正常结束时 await 不得收到该观察性错误。
            emitter.emit(IllegalStateException("execution ended without terminal result"))
        } catch (cancelled: CancellationException) {
            // 取消：保留原取消异常，沿 Job 取消语义传播（仅取消本执行，不取消 owner parent）。
            emitter.emit(cancelled)
            throw cancelled
        } catch (throwable: Throwable) {
            // 业务/输出非取消异常：先观察，再记录到完成回执（不在体内发布），
            // 由 Job 完成回调在清理之后原样交付 await；不再向 owner parent 传播，
            // 因此不会先取消原入口包装器，也无未处理的 launch 异常。
            emitter.emit(throwable)
            completion.record(throwable)
        }
    }

    /** 按执行绑定的发射器：唯一身份捕获点，生产者只发 raw 事件。 */
    private inner class ExecutionEmitter(
        private val key: TurnKey,
        private val source: EntrySource,
    ) : BoundFactEmitter {
        @Volatile
        private var currentAttempt: Int = 1

        /** 首个执行级终态事实的独占声明位；CAS 成功即有且仅有一次落地。 */
        private val terminalClaim = AtomicBoolean(false)

        /** 是否已有终态事实（StreamResult/ExecutionFailed）。 */
        override val settled: Boolean get() = terminalClaim.get()

        override val scope: ExecutionScope
            get() = ExecutionScope(key, currentAttempt, source)

        override fun accept(fact: RuntimeFact) = emitFact(fact)

        override fun emit(event: TurnEvent) {
            val attempt = currentAttempt
            // 原始分层计数不可直接入回合身份：按事件边界归一到执行内单调序号。
            if (event is TurnEvent.RetryScheduled) currentAttempt = attempt + 1
            emitFact(RuntimeFact.StreamEvent(key, attempt, event))
        }

        /** 首个终态抢占成功才发 StreamResult；后续重复/取消不产生第二个终态事实。 */
        override fun emit(result: TurnResult) {
            if (!terminalClaim.compareAndSet(false, true)) return
            emitFact(RuntimeFact.StreamResult(key, currentAttempt, result))
        }

        /** 同上；raw 事件仍照常发射，业务异常由调用方原样抛出。 */
        override fun emit(error: Throwable) {
            if (!terminalClaim.compareAndSet(false, true)) return
            emitFact(RuntimeFact.ExecutionFailed(key, currentAttempt, error))
        }

        /** 能力准备阶段事实；已结算后不再发射（不伪造终态后的准备阶段）。 */
        override fun emitPreparation(started: Boolean) {
            if (settled) return
            emitFact(
                if (started) {
                    RuntimeFact.CapabilityPreparationStarted(key)
                } else {
                    RuntimeFact.CapabilityPreparationEnded(key)
                },
            )
        }

        /** 取消请求事实；已结算后不再发射（不伪造取消请求）。 */
        override fun emitCancellationRequest(reason: Reason, trigger: StopTrigger?) {
            if (settled) return
            emitFact(RuntimeFact.CancellationRequested(key, reason, trigger))
        }

        /** 汇点抛错不影响执行（§9.9）；start 事实也走这里。 */
        fun emitFact(fact: RuntimeFact) {
            try {
                sink.accept(fact)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val ORIGIN_EXECUTOR = "EXECUTOR"
        const val CODE_STOP_REQUEST = "STOP_REQUEST"
        const val CODE_CANCEL_REQUEST = "CANCEL_REQUEST"
        val STOP_REQUEST_REASON = Reason(CODE_STOP_REQUEST, ORIGIN_EXECUTOR)
        val CANCEL_REQUEST_REASON = Reason(CODE_CANCEL_REQUEST, ORIGIN_EXECUTOR)
    }
}
