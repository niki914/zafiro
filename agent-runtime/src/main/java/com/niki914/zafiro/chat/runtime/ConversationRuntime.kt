package com.niki914.zafiro.chat.runtime

import com.niki914.okia.conversation.Conversation
import com.niki914.zafiro.chat.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 会话操作后端回执：后端实际分配/绑定的持久化 ID。
 * 宿主直接 stream 等无持久身份的会话返回 null；不从 OKIA 会话 id 推断 Room 身份。
 */
data class OperationReceipt(val persistedId: String? = null)

/**
 * 会话操作后端：由 App / 既有来源层（HomeChat conversations、Service reset）实现，
 * Runtime 只按显式 [ConversationRuntime.operate] 命令调用；仓储/建档/reset 逻辑不进入 Runtime。
 *
 * 约定：
 * - 实际执行原操作；失败或取消以原异常抛出（Runtime 记录后原样重抛，旧入口错误投影不变）。
 * - 返回后端实际分配的回执（[OperationReceipt.persistedId]），不得从 OKIA 会话 id 猜测。
 */
fun interface ConversationOperationBackend {
    suspend fun execute(operation: ConversationOperation): OperationReceipt
}

/**
 * 主进程对话运行时门面（T-06，AC4/AC7/AC9）。
 *
 * 每个实例组装**一份** [ConversationStateReducer] + [ConversationPermissionObserver] +
 * [ConversationExecutor]；无全局单例、无额外 Job/会话注册表。
 *
 * - 只读观察：仅 [snapshot] 与 [conversation] 对外只读，订阅无业务副作用。
 * - 执行所有权：执行 Job 由 executor 持有并与原 [ExecutionOwner] 寿命绑定；[submit]
 *   只返回 [TurnHandle]，客户端不获得 Job 所有权。
 * - 调用者分离：`caller` 仅作诊断标记，不决定停止策略；停止策略一律取**捕获的目标
 *   执行** owner/source（见 [ConversationExecutor.stop]），非调用者 surface。
 * - 构造与观察不触发业务：只订阅 [ConversationExecutor.conversation]（含空闲/清空更新）
 *   并在注入 scope 寿命内转发会话级事实，不自动 submit/reset/restore/create。
 * - 操作走后端：Runtime 不吞并 Repository/建档/reset 逻辑，不自动执行任何操作。
 *
 * @param scope 组装方注入的 Runtime 寿命作用域（仅承载会话观察，不承载执行）。
 * @param engine 执行引擎接缝，默认生产 [LlmControllerEngine]。
 * @param operationBackend 会话操作后端；未注入时 [operate] 明确失败，不伪装成功。
 * @param clock 可注入时钟，终测确定性。
 */
class ConversationRuntime(
    scope: CoroutineScope,
    engine: ConversationEngine = LlmControllerEngine,
    private val operationBackend: ConversationOperationBackend? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val reducer = ConversationStateReducer(clock)
    private val permissions = ConversationPermissionObserver(reducer)
    private val executor = ConversationExecutor(engine, reducer, permissions)

    init {
        // 同源内容观察：App/Runtime 寿命内订阅，含初始/空闲/清空（StateFlow 当前值），
        // 不在每次执行内单独起观察协程。UNDISPATCHED 让首帧（当前值，可能已有会话内容）
        // 在构造线程同步归约，立即订阅 snapshot 的观察者不会先看到空快照；首个挂起点后
        // 继续在注入 scope 的调度器上运行，Job 仍是 scope 子 Job。仅转发会话级事实，
        // 不触发任何 submit/reset/restore/create。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            executor.conversation.collect { conversation ->
                reducer.accept(
                    RuntimeFact.ConversationUpdated(
                        runtimeConversationId = conversation?.id,
                        conversation = conversation,
                    ),
                )
            }
        }
    }

    /** 只读完整状态快照；晚订阅立即取得最新完整值。 */
    val snapshot: StateFlow<ConversationSnapshot> get() = reducer.snapshot

    /**
     * 原始内容源，与持久化同源且不复制；会话实例切换/清空自动重发射。
     * 持久化订阅本流，不由每次状态 version 驱动（不为日志/状态变化新增保存）。
     */
    val conversation: StateFlow<Conversation?> get() = executor.conversation

    /** 发起一次执行；output 仍是原有序回调，不从 snapshot 重建。 */
    fun submit(
        input: TurnInput,
        owner: ExecutionOwner,
        output: suspend (LlmStreamEvent) -> Unit,
    ): TurnHandle = executor.submit(input, owner, output)

    /**
     * 统一停止命令（AC9）：按回合定位，委托 executor 按捕获的目标 owner/source 决定次序；
     * `caller` 仅诊断，不影响策略。过期/已结算目标返回 [CommandOutcome.IgnoredStaleTarget]。
     */
    suspend fun stop(turn: TurnKey, caller: CommandCaller): CommandOutcome = executor.stop(turn)

    /**
     * 会话操作（create/restore/switch/reset）：只显式调用时执行；无后端时不伪装成功。
     * 后端异常/取消记录为 [OperationPhase.Failed] 后原样重抛。
     */
    suspend fun operate(
        operation: ConversationOperation,
        caller: CommandCaller,
    ): OperationOutcome {
        val backend = operationBackend
        if (backend == null) {
            // 未注入后端：只如实记录一次已尝试的失败（不记 InProgress、不伪造后端工作）。
            val reason = Reason(CODE_BACKEND_MISSING, ORIGIN_RUNTIME)
            reducer.accept(
                RuntimeFact.OperationEvent(operation, OperationPhase.Failed, reason),
            )
            return OperationOutcome.Failed(reason)
        }
        reducer.accept(RuntimeFact.OperationEvent(operation, OperationPhase.InProgress))
        try {
            val receipt = backend.execute(operation)
            reducer.accept(
                RuntimeFact.OperationEvent(
                    operation = operation,
                    phase = OperationPhase.Succeeded,
                    persistedId = receipt.persistedId,
                ),
            )
            return OperationOutcome.Succeeded
        } catch (throwable: Throwable) {
            // 记录原始失败/取消后原样重抛，沿旧入口错误投影。
            val reason = if (throwable is CancellationException) {
                Reason("CANCELLED", ORIGIN_RUNTIME, throwable.message)
            } else {
                Reason(throwable::class.java.simpleName, ORIGIN_RUNTIME, throwable.message)
            }
            reducer.accept(
                RuntimeFact.OperationEvent(operation, OperationPhase.Failed, reason),
            )
            throw throwable
        }
    }

    /**
     * 授权响应：委托同一 permission observer 完成原等待器（前台工具确认、屏控、
     * 后台注册的实际等待器）；系统权限无 resolver 时返回 [CommandOutcome.UnsupportedAction]。
     */
    suspend fun respond(
        turn: TurnKey,
        requestId: String,
        allowed: Boolean,
        caller: CommandCaller,
    ): CommandOutcome = permissions.respond(turn, requestId, allowed)

    /**
     * owner 寿命结束（原 onCleared/Binder death/destroy/reset 适配器入口）：
     * 按原触发顺序取消该 owner 的执行，绝不自动 reset、不扩大作用域。
     */
    fun ownerEnded(owner: ExecutionOwner, trigger: StopTrigger) =
        executor.ownerEnded(owner, trigger)

    // ── 原入口兼容钩子：new/load/delete-current/reset 的既有取消/join/引擎停止次序 ──

    /** 定向取消捕获 Job（等价原局部 `streamJob.cancel()`）；过期身份返回 false。 */
    fun cancel(turn: TurnKey): Boolean = executor.cancel(turn)

    /** 等待目标执行结束（等价原 `cancelAndJoin`）；不暴露 Job。 */
    suspend fun await(turn: TurnKey) = executor.await(turn)

    /** 引擎侧定向停止（等价原 `stopCurrentRound`）；不触碰过期回合。 */
    suspend fun stopTurn(turn: TurnKey): Boolean = executor.stopTurn(turn)

    // ── 后台 responder 装配缝：App 注册实际等待器；按实例比较解除，不误删新请求 ──

    fun registerBackgroundResponder(responder: (requestId: String, allowed: Boolean) -> Boolean) =
        permissions.registerBackgroundResponder(responder)

    fun unregisterBackgroundResponder(responder: (requestId: String, allowed: Boolean) -> Boolean) =
        permissions.unregisterBackgroundResponder(responder)

    private companion object {
        const val ORIGIN_RUNTIME = "RUNTIME"
        const val CODE_BACKEND_MISSING = "OPERATION_BACKEND_MISSING"
    }
}
