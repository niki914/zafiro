package com.niki914.zafiro.chat.runtime

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.error.LLMError
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 串行状态归约器（T-03，AC5）：[RuntimeFact] → 不可变 [ConversationSnapshot]。
 *
 * 规则（design §9.6-9.10）：
 * - 纯同步归约：无 Job、无 IO、无引擎/UI/权限决策；[accept] 用监视器把整次
 *   归约与发布串行化，不依赖调用方声明「同一协程」（授权观察与流事实可能来自
 *   不同协程）。
 * - 消息边界取自真实事件源：OKIA 每段尝试新建 `StreamState`，段首 index 从 0
 *   重新计；只有开始新块的块首事件（Started/Ready/工具意图开始）参与边界判定，
 *   delta/ended 只是同一块的延续。见 [TurnState.beginMessage]。
 * - 重试取自显式 [TurnEvent.RetryScheduled]，且只丢弃**未 commit** 的失败尝试
 *   视图：RetryScheduled 也可能落在下一段开头（此时 ordinal 仍是上一段已 commit
 *   的内容），已 commit 段落按证据保留。见 [TurnState.discardFailedAttempt]。
 * - 工具意图先于 Ready 可见：OKIA 在 Ready 前不把调用放进 `partial.content`，
 *   故不伪造块位置，但把已收的 id/name/参数增量放进 `ToolObservation.call`
 *   （阶段为 Intent/Arguments，outcome 为空——参数可能不完整、不是合法 JSON）。
 * - 身份隔离：事实按 [TurnKey] 归属；旧回合事实只结算其终态（进入 lastTerminal），
 *   不覆盖新 active 回合；执行 epoch 单调水位线（不可回收、非墓碑集合）拒绝重复
 *   与陈旧 [RuntimeFact.ExecutionStarted]。等待结算的旧回合保留到真实终态；新
 *   active 结算时把仍进行中的等待回合恢复为 active，不伪造完成。
 * - 终态只结算一次且不丢原因：TurnEvent 级终态先到并保留详细 abort/timeout/
 *   failure 原因，[RuntimeFact.StreamResult]/[RuntimeFact.ExecutionFailed] 只补空缺
 *   原因、不重复迁移；结算时把在途块/工具收尾为 Interrupted，清空阻塞集合。
 * - 取消即取消：真实取消经 ExecutionFailed(CancellationException) 映射为 Cancelled，
 *   不发明 reset。
 * - 发布不可变：每次发布重建只读集合；晚订阅者经 StateFlow 取得完整最新状态。
 */
class ConversationStateReducer(
    /** 可注入时钟，终测确定性。 */
    private val clock: () -> Long = System::currentTimeMillis,
) : RuntimeFactSink {

    /** 归约+发布串行化：授权观察与流事实可能来自不同协程。 */
    private val lock = Any()

    private val flow = MutableStateFlow(initialSnapshot())

    /** 只读快照流；晚订阅立即得到完整最新状态。 */
    val snapshot: StateFlow<ConversationSnapshot> = flow.asStateFlow()

    // ── 会话状态（会话级事实直接持有） ────────────────────────────────────
    private var runtimeConversationId: String? = null
    private var persistedId: String? = null
    private var operation: OperationKind? = null
    private var operationPhase: OperationPhase = OperationPhase.None
    private var operationReason: Reason? = null
    private var conversation: Conversation? = null

    // ── 回合状态 ─────────────────────────────────────────────────────────
    private var active: TurnState? = null

    /** 已被新回合取代、等待终态结算的旧回合；契约保证 executor 终以
     * StreamResult/ExecutionFailed 结算（含取消），故不设容量上限丢弃。其中仍
     * 进行中的回合会在新 active 结算后恢复为 active（见 [promotePending]）。 */
    private val pendingSettlement = LinkedHashMap<TurnKey, TurnState>()

    /** 已接受执行的最高 epoch（executor 全局单调）。单调水位线即可拒绝重复/陈旧
     * start，无需保存已结算回合的墓碑集合。 */
    private var epochWatermark: Long? = null

    private var lastTerminal: TurnObservation? = null

    override fun accept(fact: RuntimeFact) {
        // 汇点契约：同步回调不抛异常影响业务（§9.9）。
        synchronized(lock) {
            val changed = try {
                reduce(fact)
            } catch (_: Throwable) {
                false
            }
            if (changed) publish()
        }
    }

    // ── 归约 ─────────────────────────────────────────────────────────────

    private fun reduce(fact: RuntimeFact): Boolean = when (fact) {
        is RuntimeFact.ExecutionStarted -> onExecutionStarted(fact)
        is RuntimeFact.ConversationUpdated -> onConversationUpdated(fact)
        is RuntimeFact.StreamEvent -> onStreamEvent(fact)
        is RuntimeFact.StreamResult ->
            settle(fact.key, fact.attempt) { terminal(it, phaseOf(fact.result), reasonOf(fact.result)) }
        is RuntimeFact.ExecutionFailed ->
            settle(fact.key, fact.attempt) { applyFailure(fact.error, it) }
        is RuntimeFact.OperationEvent -> onOperation(fact)
        is RuntimeFact.PermissionReported -> onPermission(fact.observation)
        is RuntimeFact.CancellationRequested -> onCancellationRequested(fact)
        is RuntimeFact.CapabilityPreparationStarted -> onCapabilityPreparation(fact.key, true)
        is RuntimeFact.CapabilityPreparationEnded -> onCapabilityPreparation(fact.key, false)
    }

    private fun onExecutionStarted(fact: RuntimeFact.ExecutionStarted): Boolean {
        val current = active
        // 重复/已跟踪身份：不新建、不抹掉已存在回合。
        if (current?.key == fact.key || pendingSettlement.containsKey(fact.key)) return false
        // epoch 全局单调：水位线以下（含相等）即重复或陈旧，已结算回合同样被挡住。
        if (epochWatermark?.let { fact.key.epoch <= it } == true) return false
        epochWatermark = fact.key.epoch
        if (current != null) pendingSettlement[current.key] = current
        active = TurnState(fact.key, fact.source)
        return true
    }

    private fun onConversationUpdated(fact: RuntimeFact.ConversationUpdated): Boolean {
        runtimeConversationId = fact.runtimeConversationId
        conversation = fact.conversation
        return true
    }

    private fun onStreamEvent(fact: RuntimeFact.StreamEvent): Boolean {
        val turn = target(fact.key) ?: return false
        if (fact.attempt > turn.attempt) turn.attempt = fact.attempt
        // 新尝试的首个非退出事件即退避结束；RetryScheduled 自身保留阻塞。
        if (fact.event !is TurnEvent.RetryScheduled) turn.blockers.remove(RETRY_BLOCKER_ID)
        applyEvent(fact.event, fact.attempt, turn)
        return true
    }

    private fun applyEvent(event: TurnEvent, attempt: Int, turn: TurnState) {
        when (event) {
            is TurnEvent.TurnStarted ->
                // 晚到的 TurnStarted 不回退已取消状态（准备期公开 stop 先到）。
                if (turn.phase == TurnPhase.Preparing) turn.phase = TurnPhase.Running

            // 块首事件：先判消息边界，再落块。delta/ended 不是块首，不参与边界。
            is TurnEvent.TextStarted -> {
                turn.beginMessage(event.index)
                turn.putBlock(attempt, event.index, BlockKind.Text, BlockPhase.Started, null, event.partial)
            }

            is TurnEvent.TextDelta ->
                turn.putBlock(attempt, event.index, BlockKind.Text, BlockPhase.Streaming, null, event.partial)

            is TurnEvent.TextEnded ->
                turn.putBlock(
                    attempt, event.index, BlockKind.Text, BlockPhase.Ended,
                    ContentBlock.Text(event.content), event.partial,
                )

            is TurnEvent.ThinkingStarted -> {
                turn.beginMessage(event.index)
                turn.putBlock(attempt, event.index, BlockKind.Thinking, BlockPhase.Started, null, event.partial)
            }

            is TurnEvent.ThinkingDelta ->
                turn.putBlock(attempt, event.index, BlockKind.Thinking, BlockPhase.Streaming, null, event.partial)

            is TurnEvent.ThinkingEnded ->
                turn.putBlock(
                    attempt, event.index, BlockKind.Thinking, BlockPhase.Ended,
                    ContentBlock.Thinking(event.content), event.partial,
                )

            // 工具意图：OKIA 在 Ready 前不把调用放进 partial.content、事件 index 是
            // 「blocks.size + pendingIndex」的预估；名称/参数增量仍要可观察。
            is TurnEvent.ToolCallStarted -> {
                turn.beginMessage(event.index)
                turn.startToolIntent(attempt, event.callId, event.toolName)
            }

            is TurnEvent.ToolCallDelta -> turn.appendToolArguments(attempt, event.index, event.delta)

            is TurnEvent.ToolCallReady -> {
                turn.beginMessage(event.index)
                turn.materializeTool(attempt, event.index, event.toolCall, event.partial)
            }

            is TurnEvent.ToolRunning -> {
                turn.putTool(
                    attempt, event.index, event.toolCall.id, ToolPhase.Running,
                    event.toolCall, null, event.partial,
                )
                // 并行工具执行：每个在途调用一个可并存阻塞点。
                val blockerId = toolBlockerId(event.index, event.toolCall.id)
                turn.blockers[blockerId] = Blocker(
                    id = blockerId,
                    kind = BlockerKind.ToolExecution,
                    toolCallId = event.toolCall.id.ifEmpty { null },
                    requestId = null,
                    retryDelayMs = null,
                    reason = null,
                )
            }

            is TurnEvent.ToolSucceeded -> {
                turn.putTool(
                    attempt, event.index, event.toolCall.id, ToolPhase.Succeeded,
                    event.toolCall, event.outcome, event.partial,
                )
                turn.blockers.remove(toolBlockerId(event.index, event.toolCall.id))
            }

            is TurnEvent.ToolFailed -> {
                // 取消补全（onInterrupt）以 Interrupted outcome 回流：区分失败与中断。
                val phase = if (event.outcome is ToolCallOutcome.Interrupted) {
                    ToolPhase.Interrupted
                } else {
                    ToolPhase.Failed
                }
                turn.putTool(
                    attempt, event.index, event.toolCall.id, phase,
                    event.toolCall, event.outcome, event.partial,
                )
                turn.blockers.remove(toolBlockerId(event.index, event.toolCall.id))
            }

            // 显式重试边界：只替换未 commit 的失败尝试视图；下一段开头的重试
            // 落在已 commit 段落上时保留既有内容（见 discardFailedAttempt）。
            is TurnEvent.RetryScheduled -> {
                turn.discardFailedAttempt()
                turn.blockers[RETRY_BLOCKER_ID] = Blocker(
                    id = RETRY_BLOCKER_ID,
                    kind = BlockerKind.RetryBackoff,
                    toolCallId = null,
                    requestId = null,
                    retryDelayMs = event.delayMs,
                    reason = Reason(event.reason, ORIGIN_OKIA),
                )
            }

            // 事件级终态：立即收尾在途块/工具并清阻塞，详细原因先到先留；
            // 结算由 StreamResult/ExecutionFailed 补空缺原因，不重复迁移。
            is TurnEvent.TurnCompleted -> terminal(turn, TurnPhase.Completed, null)
            is TurnEvent.TurnFailed -> terminal(turn, TurnPhase.Failed, reasonOf(event.error))
            is TurnEvent.TurnAborted -> terminal(turn, TurnPhase.Cancelled, reasonOf(event.cause))
            is TurnEvent.TurnIdleTimeout ->
                terminal(turn, TurnPhase.Failed, Reason("IDLE_TIMEOUT", ORIGIN_OKIA))
        }
    }

    /** 终态迁移：首次终态决定 phase/reason，之后只补空缺原因，绝不重复迁移。
     * 取消请求原因仅作首终态缺原因时的兜底，不覆盖引擎给出的 StopCause/失败原因；
     * 正常 Completed 不附带取消原因。 */
    private fun terminal(turn: TurnState, phase: TurnPhase, reason: Reason?) {
        val fallback = turn.cancelReason.takeIf { phase != TurnPhase.Completed }
        if (!turn.phase.isTerminal) {
            turn.phase = phase
            turn.reason = reason ?: fallback
        } else if (turn.reason == null) {
            turn.reason = reason ?: fallback
        }
        turn.finalizeInflight()
    }

    /** 终态结算（StreamResult / ExecutionFailed 唯一入口，只结算一次）。 */
    private fun settle(key: TurnKey, attempt: Int, apply: (TurnState) -> Unit): Boolean {
        val turn = if (active?.key == key) active else pendingSettlement[key]
        // 未跟踪或已结算：无 source/内容证据，不构造猜测性观察。
        turn ?: return false
        if (attempt > turn.attempt) turn.attempt = attempt
        apply(turn)
        val observation = turn.observation()
        if (lastTerminal == null || key.epoch >= lastTerminal!!.key.epoch) lastTerminal = observation
        if (active?.key == key) {
            active = null
            promotePending()
        }
        pendingSettlement.remove(key)
        return true
    }

    /**
     * active 结算后的可见性恢复：新回合先结束而更早提交的回合仍在运行时，把仍在
     * 进行中（事件级未终态）的最高 epoch 等待回合恢复为 active，避免其进展与阻塞
     * 随 active 清空而不可见。已事件级终态的等待回合不提升，只保留在
     * [pendingSettlement] 内等待其结算事实；不伪造完成、不丢弃在途事实。
     */
    private fun promotePending() {
        var candidate: TurnState? = null
        for (pending in pendingSettlement.values) {
            if (pending.phase.isTerminal) continue
            val current = candidate
            if (current == null || pending.key.epoch > current.key.epoch) candidate = pending
        }
        val next = candidate ?: return
        active = next
        pendingSettlement.remove(next.key)
    }

    private fun phaseOf(result: TurnResult): TurnPhase = when (result) {
        is TurnResult.Completed -> TurnPhase.Completed
        is TurnResult.Failed -> TurnPhase.Failed
        is TurnResult.Aborted -> TurnPhase.Cancelled
        TurnResult.IdleTimeout -> TurnPhase.Failed
    }

    private fun reasonOf(result: TurnResult): Reason? = when (result) {
        is TurnResult.Completed -> null
        is TurnResult.Failed -> reasonOf(result.error)
        is TurnResult.Aborted -> reasonOf(result.cause)
        TurnResult.IdleTimeout -> Reason("IDLE_TIMEOUT", ORIGIN_OKIA)
    }

    private fun applyFailure(error: Throwable, turn: TurnState) {
        if (error is CancellationException) {
            // 真实取消：按取消结算，不发明 reset；已捕获的取消请求原因（公开 stop /
            // owner 触发）优先于通用 CANCELLED 兜底。
            terminal(
                turn,
                TurnPhase.Cancelled,
                turn.cancelReason ?: Reason("CANCELLED", ORIGIN_EXECUTOR, error.message),
            )
        } else {
            // 未进 TurnResult 的框架异常（配置错误等）。
            terminal(turn, TurnPhase.Failed, Reason("EXECUTION_FAILED", ORIGIN_EXECUTOR, describe(error)))
        }
    }

    private fun onOperation(fact: RuntimeFact.OperationEvent): Boolean {
        operation = when (fact.operation) {
            is ConversationOperation.Create -> OperationKind.Create
            is ConversationOperation.Restore -> OperationKind.Restore
            is ConversationOperation.Switch -> OperationKind.Switch
            ConversationOperation.Reset -> OperationKind.Reset
        }
        operationPhase = fact.phase
        operationReason = fact.reason
        // 只有已提交的成功状态更新当前持久身份；失败操作保留先前身份。
        // create 的 firstUserInput 只用于后端建档负载，不携带身份，身份只可能来自后端回执；
        // restore/switch 以后端回执优先，无回执时用请求目标。均不从 OKIA 会话 id 推断。
        when (val op = fact.operation) {
            is ConversationOperation.Restore ->
                if (fact.phase == OperationPhase.Succeeded) {
                    persistedId = fact.persistedId ?: op.persistedId
                }

            is ConversationOperation.Switch ->
                if (fact.phase == OperationPhase.Succeeded) {
                    persistedId = fact.persistedId ?: op.persistedId
                }

            ConversationOperation.Reset ->
                if (fact.phase == OperationPhase.Succeeded) {
                    runtimeConversationId = null
                    persistedId = null
                    conversation = null
                }

            is ConversationOperation.Create ->
                if (fact.phase == OperationPhase.Succeeded) {
                    fact.persistedId?.let { persistedId = it }
                }
        }
        return true
    }

    /**
     * 取消请求：目标仍非终态时进入 Cancelling，并保留请求原因（仅终态缺原因时兜底）。
     * 已结算/未知目标不构造猜测性观察，旧回合请求不污染新回合。
     */
    private fun onCancellationRequested(fact: RuntimeFact.CancellationRequested): Boolean {
        val turn = target(fact.key) ?: return false
        if (turn.phase.isTerminal) return false
        turn.phase = TurnPhase.Cancelling
        if (turn.cancelReason == null) turn.cancelReason = fact.reason
        return true
    }

    /**
     * 能力准备显式阻塞：begin 挂 [BlockerKind.CapabilityPreparation]（与权限/工具/
     * 退避并存），end 在 finally 清除；终态后到达的事实被隔离。
     */
    private fun onCapabilityPreparation(key: TurnKey, started: Boolean): Boolean {
        val turn = target(key) ?: return false
        if (started) {
            if (turn.phase.isTerminal) return false
            turn.blockers[CAPABILITY_BLOCKER_ID] = Blocker(
                id = CAPABILITY_BLOCKER_ID,
                kind = BlockerKind.CapabilityPreparation,
                toolCallId = null,
                requestId = null,
                retryDelayMs = null,
                reason = CAPABILITY_PREPARATION_REASON,
            )
        } else {
            turn.blockers.remove(CAPABILITY_BLOCKER_ID)
        }
        return true
    }

    private fun onPermission(observation: PermissionRequestObservation): Boolean {
        val turn = target(observation.turn) ?: return false // 旧回合响应不污染快照
        turn.permissions[observation.requestId] = observation
        val blockerId = PERM_BLOCKER_PREFIX + observation.requestId
        when (observation.phase) {
            PermissionPhase.Requested, PermissionPhase.Processing ->
                turn.blockers[blockerId] = Blocker(
                    id = blockerId,
                    kind = BlockerKind.Permission,
                    toolCallId = observation.toolCallId,
                    requestId = observation.requestId,
                    retryDelayMs = null,
                    reason = observation.reason,
                )

            else -> turn.blockers.remove(blockerId)
        }
        return true
    }

    // ── 定位与辅助 ───────────────────────────────────────────────────────

    /** 事实归属：active 优先；否则允许更新等待结算的旧回合（不覆盖 active）。 */
    private fun target(key: TurnKey): TurnState? =
        if (active?.key == key) active else pendingSettlement[key]

    private fun reasonOf(error: LLMError): Reason =
        Reason(error.code.name, ORIGIN_OKIA, error.message)

    private fun reasonOf(cause: StopCause): Reason =
        Reason(cause.name, ORIGIN_OKIA)

    private fun describe(error: Throwable): String =
        "${error::class.java.simpleName}: ${error.message}"

    private fun toolBlockerId(index: Int, callId: String): String =
        TOOL_BLOCKER_PREFIX + callId.ifEmpty { "block$index" }

    private fun publish() {
        flow.value = ConversationSnapshot(
            version = flow.value.version + 1,
            updatedAtMillis = clock(),
            session = SessionObservation(
                runtimeConversationId = runtimeConversationId,
                persistedId = persistedId,
                operation = operation,
                phase = operationPhase,
                reason = operationReason,
            ),
            conversation = conversation,
            active = active?.observation(),
            lastTerminal = lastTerminal,
        )
    }

    private fun initialSnapshot(): ConversationSnapshot = ConversationSnapshot(
        version = 0L,
        updatedAtMillis = clock(),
        session = SessionObservation(null, null, null, OperationPhase.None, null),
        conversation = null,
        active = null,
        lastTerminal = null,
    )

    private companion object {
        const val ORIGIN_OKIA = "OKIA"
        const val ORIGIN_EXECUTOR = "EXECUTOR"
        const val RETRY_BLOCKER_ID = "retry"
        const val TOOL_BLOCKER_PREFIX = "tool:"
        const val PERM_BLOCKER_PREFIX = "perm:"
        const val CAPABILITY_BLOCKER_ID = "capability"
        val CAPABILITY_PREPARATION_REASON =
            Reason("CAPABILITY_PREPARATION", ORIGIN_EXECUTOR)
    }
}

private val TurnPhase.isTerminal: Boolean
    get() = this == TurnPhase.Completed || this == TurnPhase.Cancelled || this == TurnPhase.Failed

private val ToolPhase.isTerminal: Boolean
    get() = this == ToolPhase.Succeeded || this == ToolPhase.Failed || this == ToolPhase.Interrupted

/**
 * 未 Ready 工具调用的暂存观察（OKIA：Ready 前不占位 `partial.content`）。
 * 名称与参数增量原样保存，[call] 暴露的是可能不完整、未必是合法 JSON 的
 * `argumentsJson`；阶段停在 Intent/Arguments，读到 Ready 才换成最终事实。
 */
private class PendingTool(
    val attempt: Int,
    var callId: String?,
    var name: String?,
    val arguments: StringBuilder,
    var phase: ToolPhase,
) {
    fun call(): ContentBlock.ToolCall? =
        if (callId == null && name == null && arguments.isEmpty()) {
            null
        } else {
            ContentBlock.ToolCall(
                id = callId.orEmpty(),
                name = name.orEmpty(),
                argumentsJson = arguments.toString(),
            )
        }
}

/** 单回合可变归约状态；仅在 [TurnState.observation] 处转为不可变投影。 */
private class TurnState(
    val key: TurnKey,
    val source: EntrySource,
) {
    var phase: TurnPhase = TurnPhase.Preparing

    /** ExecutionStarted 无 attempt；首个流事实前呈 0（未知哨兵）。 */
    var attempt: Int = 0
    var reason: Reason? = null

    /** 已收到的取消请求原因；仅终态缺原因时兜底，不覆盖引擎终态原因。 */
    var cancelReason: Reason? = null

    /** 同 attempt 内的段序号（工具循环多段）；见 [beginMessage]。 */
    var ordinal: Int = 0

    /** 已落地内容块（text/thinking/已 Ready 的工具块），index 为事件给出的块位置。 */
    val blocks = LinkedHashMap<BlockKey, BlockObservation>()

    /** 已落地工具观察，按块键索引。 */
    val tools = LinkedHashMap<BlockKey, ToolObservation>()

    /** 在途（未 Ready）工具调用，顺序即 OKIA `pendingToolCalls` 顺序。 */
    val pendingTools = mutableListOf<PendingTool>()

    val blockers = LinkedHashMap<String, Blocker>()
    val permissions = LinkedHashMap<String, PermissionRequestObservation>()

    /**
     * 消息边界（工具循环下一段）：OKIA 每段尝试新建 StreamState，段首 index 从
     * 0 重新计；同段内 blocks.size 只增，故 index==0 的块首事件在当前 ordinal
     * 已有块且全部已 Ended（上一段已 commit）时即进入下一段。in-flight 文本/
     * 思考与工具意图的预估 index 可能同为 0，但它们未 Ended，不会触发边界；
     * delta/ended 从不参与。
     */
    fun beginMessage(index: Int) {
        if (index != 0) return
        val current = blocks.keys.filter { it.messageOrdinal == ordinal }
        if (current.isEmpty()) return
        if (current.any { blocks.getValue(it).phase != BlockPhase.Ended }) return
        // 上一段结束时仍未 Ready 的调用不会进入内容，但不静默丢观察。
        finalizePending(Reason.Unknown)
        ordinal++
    }

    /**
     * 段首重试只丢弃**未 commit** 的失败尝试视图。已 commit 段落的证据：该
     * ordinal 内已有工具终态事件——RealAgentLoop 先 `onCommit(assistant)` 再
     * executeTools，故 ToolRunning/Succeeded/Failed 出现即消息已 commit；而
     * 下一段开头（发送阶段失败）的 RetryScheduled 落在这种 ordinal 上时必须保留。
     * 未 commit 的 partial 被真实源丢弃（每次段尝试独立 StreamState），归约同步替换。
     */
    fun discardFailedAttempt() {
        if (tools.values.any { it.block.messageOrdinal == ordinal && it.phase.isTerminal }) return
        blocks.keys.filter { it.messageOrdinal == ordinal }.forEach { blocks.remove(it) }
        tools.keys.filter { it.messageOrdinal == ordinal }.forEach { tools.remove(it) }
        pendingTools.clear()
    }

    /**
     * OKIA `state.blocks.size`：已 flush 的块数。events index = blocks.size + pendingIndex，
     * 而 in-flight 的文本/思考不在 blocks 中，故本状态里只计非 Started/Streaming 块。
     */
    private fun okiaBlockCount(): Int = blocks.values.count {
        it.key.messageOrdinal == ordinal && it.phase != BlockPhase.Started && it.phase != BlockPhase.Streaming
    }

    /** 在途调用在 OKIA `pendingToolCalls` 中的位置（Delta/Ready 的事件 index 反推）。 */
    private fun pendingAt(index: Int): PendingTool? {
        if (pendingTools.isEmpty()) return null
        val position = (index - okiaBlockCount()).coerceIn(0, pendingTools.lastIndex)
        return pendingTools[position]
    }

    /** ToolCallStarted：OKIA 每次都 append 一个 pending（不去重）。 */
    fun startToolIntent(attempt: Int, callId: String, name: String) {
        pendingTools += PendingTool(
            attempt = attempt,
            callId = callId.ifEmpty { null },
            name = name.ifEmpty { null },
            arguments = StringBuilder(),
            phase = ToolPhase.Intent,
        )
    }

    /** ToolCallDelta：TurnEvent 不带 callId，按事件 index 定位同一在途调用并累积参数。 */
    fun appendToolArguments(attempt: Int, index: Int, delta: String) {
        val pending = pendingAt(index) ?: PendingTool(
            attempt = attempt,
            callId = null,
            name = null,
            arguments = StringBuilder(),
            phase = ToolPhase.Arguments,
        ).also { pendingTools += it }
        pending.arguments.append(delta)
        pending.phase = ToolPhase.Arguments
    }

    /** ToolCallReady：以事件 index 为块的最终事实源，callId 优先匹配在途调用。 */
    fun materializeTool(
        attempt: Int,
        index: Int,
        toolCall: ContentBlock.ToolCall,
        partial: AssistantMessage,
    ) {
        val pending = pendingTools.firstOrNull { toolCall.id.isNotEmpty() && it.callId == toolCall.id }
            ?: pendingAt(index)
        if (pending != null) pendingTools.remove(pending)
        val blockKey = BlockKey(key, attempt, ordinal, index)
        blocks[blockKey] = BlockObservation(
            key = blockKey,
            kind = BlockKind.ToolCall,
            phase = BlockPhase.Ended,
            content = toolCall,
            partial = partial,
        )
        tools[blockKey] = ToolObservation(
            block = blockKey,
            callId = toolCall.id.ifEmpty { null },
            phase = ToolPhase.Ready,
            call = toolCall,
            outcome = null,
            reason = null,
        )
    }

    fun putBlock(
        attempt: Int,
        index: Int,
        kind: BlockKind,
        phase: BlockPhase,
        content: ContentBlock?,
        partial: AssistantMessage,
    ) {
        val blockKey = BlockKey(key, attempt, ordinal, index)
        val existing = blocks[blockKey]
        blocks[blockKey] = BlockObservation(
            key = blockKey,
            kind = kind,
            phase = phase,
            content = content ?: existing?.content,
            partial = partial,
        )
    }

    fun putTool(
        attempt: Int,
        index: Int,
        callId: String,
        phase: ToolPhase,
        call: ContentBlock.ToolCall?,
        outcome: ToolCallOutcome?,
        partial: AssistantMessage,
    ) {
        val blockKey = BlockKey(key, attempt, ordinal, index)
        if (call != null && blocks[blockKey] == null) {
            // 防御：缺少 Ready 事实时仍保证工具与块一一对应。
            blocks[blockKey] = BlockObservation(
                key = blockKey,
                kind = BlockKind.ToolCall,
                phase = BlockPhase.Ended,
                content = call,
                partial = partial,
            )
        }
        val existing = tools[blockKey]
        tools[blockKey] = ToolObservation(
            block = blockKey,
            callId = callId.ifEmpty { null } ?: existing?.callId,
            phase = phase,
            call = call ?: existing?.call,
            outcome = outcome ?: existing?.outcome,
            reason = existing?.reason,
        )
    }

    /** 在途调用收尾：不再呈现为进行中，也不静默丢观察；块键用可落地的预估位置。 */
    private fun finalizePending(reason: Reason) {
        val base = blocks.values.count { it.key.messageOrdinal == ordinal }
        pendingTools.forEachIndexed { position, pending ->
            val blockKey = BlockKey(key, pending.attempt, ordinal, base + position)
            tools[blockKey] = ToolObservation(
                block = blockKey,
                callId = pending.callId,
                phase = ToolPhase.Interrupted,
                call = pending.call(),
                outcome = null,
                reason = reason,
            )
        }
        pendingTools.clear()
    }

    /** 终态收尾：在途块/工具不再呈现为进行中，并清空该回合的观察阻塞。 */
    fun finalizeInflight() {
        val fallback = reason ?: Reason.Unknown
        for ((k, b) in blocks.toList()) {
            if (b.phase == BlockPhase.Started || b.phase == BlockPhase.Streaming) {
                blocks[k] = b.copy(phase = BlockPhase.Interrupted)
            }
        }
        for ((k, t) in tools.toList()) {
            if (!t.phase.isTerminal) {
                tools[k] = t.copy(phase = ToolPhase.Interrupted, reason = t.reason ?: fallback)
            }
        }
        finalizePending(fallback)
        blockers.clear()
    }

    fun observation(): TurnObservation {
        val blockOrder = compareBy<BlockObservation>(
            { it.key.attempt }, { it.key.messageOrdinal }, { it.key.index },
        )
        val toolOrder = compareBy<ToolObservation>(
            { it.block.attempt }, { it.block.messageOrdinal }, { it.block.index },
        )
        // 在途调用预占的位置：已落地块数 + 在途序位（Ready 时会先 flush 再插入）。
        val base = blocks.values.count { it.key.messageOrdinal == ordinal }
        val pendingViews = pendingTools.mapIndexed { position, pending ->
            ToolObservation(
                block = BlockKey(key, pending.attempt, ordinal, base + position),
                callId = pending.callId,
                phase = pending.phase,
                call = pending.call(),
                outcome = null,
                reason = null,
            )
        }
        return TurnObservation(
            key = key,
            source = source,
            // 显式等待点存在时呈 Waiting；终态不受阻塞集合影响（已清空）。
            phase = if (phase == TurnPhase.Preparing || phase == TurnPhase.Running) {
                if (blockers.isNotEmpty()) TurnPhase.Waiting else phase
            } else {
                phase
            },
            attempt = attempt,
            blocks = blocks.values.sortedWith(blockOrder),
            tools = (tools.values + pendingViews).sortedWith(toolOrder),
            blockers = blockers.values.toSet(),
            permissions = permissions.values.toList(),
            // 取消请求已捕获但尚无终态时，用请求原因解释当前 Cancelling；
            // 已有终态原因时不覆盖（引擎 StopCause/失败原因优先），Completed 不继承。
            reason = reason ?: cancelReason?.takeIf { phase == TurnPhase.Cancelling },
        )
    }
}
