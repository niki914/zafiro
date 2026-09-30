package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

// 存废：阶段 5 删除（委托实现：命令内部转发到 LLMController；架空完成后删除）

/**
 * `Agent` 的实现侧。
 *
 * 本层真做的三件事：草稿（含待落盘图片）、折叠成 [conversation]（进程内唯一的
 * 折叠点）、会话载入。执行仍转发遗留 `LLMController`：`stream` / `stop` /
 * `discard` 是过渡转发，随 M10 删除。
 *
 * 依赖经服务注册表取（组合根只负责 install），不出现带参数的构造函数。
 *
 * 门禁是自有 [roundToken] / [roundActive]，与宿主 `AgentRuntimeService.activeTurn`
 * 的 CAS 互不可见（已知偏差，宿主切真源时合并）。
 */
object AgentImpl : Agent {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 持久化端口：由 app 侧实现并在组合根 install。 */
    private fun store(): ConversationStore_Tmp = requireService()

    private const val LOG_TAG = "niki914_zafiro_AgentImpl"

    /** 同态纯文本增长的发射间隔：thinking 全量重发的刷屏主凶压到此粒度。 */
    private const val STATUS_TEXT_THROTTLE_MS = 500L

    /** ingest 管线统一转码，用户附件按 jpeg 声明。 */
    private const val USER_IMAGE_MIME = "image/jpeg"

    private val draftFlow = MutableStateFlow(Draft())
    private val conversationFlow = MutableStateFlow(Conversation())

    private val statusFlow = MutableStateFlow<AgentState>(AgentState.Idle())

    /** 当前在途审批（显示用；结算靠 Approver.decide 返回值，本 flow 只进 status 投影）。 */
    private val approvalFlow = MutableStateFlow<ApprovalRequest?>(null)

    private val roundActive = MutableStateFlow(false)
    private var streamJob: Job? = null
    private var roundToken = 0

    /** 状态节流：同态纯文本增长的发射门。终态/换态/新段直通，不丢尾。 */
    private var lastStatusEmitMs = 0L

    /** 归约器辅助态：保留跨事件的思考槽与待补全工具占位项。 */
    private var reduced = Reduced(Conversation())
    private var reducedStatus = AgentStateReducer.reset()

    override val conversation: StateFlow<Conversation> = conversationFlow.asStateFlow()
    override val draft: StateFlow<Draft> = draftFlow.asStateFlow()

    /**
     * 对外状态：审批在途时 [AgentState.WaitingApproval] 覆盖引擎态。
     * 串行工具链下同时只有一个在途请求；并发到达时首个胜出、其余随
     * `decideApproval` 的 cancel 被丢弃（边缘业务，不做队列）。
     */
    override val status: StateFlow<AgentState> =
        combine(statusFlow, approvalFlow) { engine, pending ->
            pending?.let { AgentState.WaitingApproval(it) } ?: engine
        }.stateIn(scope, SharingStarted.Eagerly, AgentState.Idle())

    init {
        // 草稿里的 Pending 项由实现侧落盘并归约成 Ready（契约的图片写入路径）
        scope.launch {
            draftFlow.collect { draft ->
                for (pending in draft.images.filterIsInstance<DraftImage.Pending>()) ingest(pending)
            }
        }
    }

    override fun updateDraft(transform: (Draft) -> Draft) {
        draftFlow.value = transform(draftFlow.value)
    }

    override fun clearDraft() {
        draftFlow.value = Draft()
    }

    override fun stream(): TurnStart {
        val draft = draftFlow.value
        if (draft.text.isBlank() && draft.images.isEmpty() && draft.files.isEmpty()) {
            return TurnStart.DraftEmpty
        }
        if (!roundActive.compareAndSet(expect = false, update = true)) return TurnStart.Busy

        val query = draft.text
        val images = draft.images.mapNotNull { image ->
            when (image) {
                is DraftImage.Pending -> null
                is DraftImage.Ready -> image.attachment
            }
        }
        // 文件引用同一次归约里取走：不拷贝、没有异步过程，所以不等待任何东西
        val files = draft.files
        // okia 边界上的形态（ContentBlock.Image）；契约侧叫 images，两者不是同一个列表
        val contentImages = images.map {
            ContentBlock.Image(it.path, it.mimeType ?: USER_IMAGE_MIME)
        }
        // 发起即清空草稿：写入与清空在同一次归约里，覆盖窗口只有一帧
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.startRound()
        emitStatus(reducedStatus.status)
        foldWith(ConversationReducer.startTurn(conversationFlow.value, query, images, files))

        val token = ++roundToken
        streamJob = scope.launch {
            try {
                val conversationId = ensureConversation(query)
                store().saveDraft(conversationId, "")
                Logger.i(LOG_TAG, "round started conversationId=${conversationId.value} queryLength=${query.length}")
                LLMController.stream(query = query, images = contentImages, files = files)
                    .collect { event ->
                        fold(event)
                    }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Logger.e(LOG_TAG, "round failed errorType=${throwable::class.simpleName} message=${throwable.message}")
                val message = throwable.message?.trim()?.takeIf(String::isNotEmpty)
                if (message != null) fold(LlmStreamEvent.Error(message = message))
                else {
                    reducedStatus = AgentStateReducer.interrupt(reducedStatus)
                    emitStatus(reducedStatus.status)
                }
            } finally {
                if (roundToken == token && statusFlow.value !is AgentState.Stopping) {
                    if (statusFlow.value.isRunning) {
                        reducedStatus = AgentStateReducer.interrupt(reducedStatus)
                        emitStatus(reducedStatus.status)
                    }
                    streamJob = null
                    roundActive.value = false
                }
            }
        }
        return TurnStart.Started
    }

    override fun stop() {
        if (!roundActive.value || statusFlow.value is AgentState.Stopping) return

        conversationFlow.value = ConversationReducer.interrupt(conversationFlow.value)
        reducedStatus = AgentStateReducer.stopping(reducedStatus)
        emitStatus(reducedStatus.status)

        val currentJob = streamJob
        val token = roundToken
        scope.launch {
            try {
                LLMController.stopCurrentRound()
                currentJob?.cancelAndJoin()
            } finally {
                if (roundToken == token) {
                    reducedStatus = AgentStateReducer.interrupt(reducedStatus)
                    emitStatus(reducedStatus.status)
                    streamJob = null
                    roundActive.value = false
                }
            }
        }
    }

    override fun discard() {
        releaseRound()
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.reset()
        emitStatus(reducedStatus.status)
        scope.launch {
            // 调用点不再维持顺序：先停后关在实现内部（OKIA §8.7 #5）
            LLMController.stopCurrentRound()
            LLMController.resetConversation()
        }
    }

    override suspend fun load(id: ConversationId) {
        releaseRound()
        reducedStatus = AgentStateReducer.reset()
        emitStatus(reducedStatus.status)
        // 先停（终止回合 + kill 工具资源）再换树：close 撞活跃回合由实现侧兜住
        LLMController.stopCurrentRound()
        val stored = store().load(id)
        if (stored == null) {
            Logger.w(LOG_TAG, "load skipped notFound id=${id.value}")
            return
        }
        LLMController.openSession(stored.snapshot)
        store().setLastOpened(id)
        reduced = Reduced(stored.conversation.copy(id = id))
        conversationFlow.value = stored.conversation.copy(id = id)
        draftFlow.value = Draft(text = stored.draftText)
        Logger.i(LOG_TAG, "loaded id=${id.value} turns=${stored.conversation.turns.size}")
    }

    private val approvers = CopyOnWriteArrayList<Approver>()

    override fun addApprover(approver: Approver) {
        if (!approvers.contains(approver)) {
            approvers.add(approver)
        }
    }

    override fun removeApprover(approver: Approver) {
        approvers.remove(approver)
    }

    /**
     * 并发询问所有已注册的 Approver，首个非 Abstain 决策胜出，其余来源随之取消。
     * 无 Approver 或全部弃权则 Deny。
     */
    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision = coroutineScope {
        val currentApprovers = approvers.toList()
        if (currentApprovers.isEmpty()) return@coroutineScope ApprovalDecision.Deny

        approvalFlow.value = request
        val deferred = CompletableDeferred<ApprovalDecision>()
        val jobs = currentApprovers.map { approver ->
            launch {
                try {
                    val decision = approver.decide(request)
                    if (decision != ApprovalDecision.Abstain) {
                        deferred.complete(decision)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
        }
        // 全部来源都结束仍无人结算（全弃权）→ 拒绝：弃权不是裁决，不能把请求悬在那里
        val allAbstained = launch {
            jobs.joinAll()
            deferred.complete(ApprovalDecision.Deny)
        }

        try {
            deferred.await()
        } finally {
            approvalFlow.value = null
            jobs.forEach { it.cancel() }
            allAbstained.cancel()
        }
    }

    /** 单测复位：进程内单例状态跨用例保留（同 `LLMController.resetForTest`）。 */
    internal fun clearForTest() {
        releaseRound()
        approvers.clear()
        approvalFlow.value = null
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.reset()
        statusFlow.value = reducedStatus.status
        lastStatusEmitMs = 0L
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 退役当前回合的门禁：旧回合的 finally 不再改它（token 已推进）。 */
    private fun releaseRound() {
        roundToken++
        roundActive.value = false
        streamJob?.cancel()
        streamJob = null
    }

    /** 会话树 id 即 Room 会话 id（okia 惰性建实例，首轮发起时建档）。 */
    private suspend fun ensureConversation(firstUserInput: String): ConversationId {
        conversationFlow.value.id?.let { return it }
        val sessionId = ConversationId(LLMController.ensureSession())
        // 建档可能已经由对话页做过：`createConversation` 的 DAO 冲突策略是 ABORT，
        // 重复 insert 会抛异常并让整轮发不出去
        if (store().exists(sessionId)) {
            Logger.i(LOG_TAG, "conversation reused id=${sessionId.value}")
        } else {
            store().create(sessionId, firstUserInput)
            Logger.i(LOG_TAG, "conversation created id=${sessionId.value}")
        }
        store().setLastOpened(sessionId)
        applySessionId(sessionId)
        return sessionId
    }

    internal fun applySessionId(sessionId: ConversationId) {
        reduced = reduced.copy(conversation = reduced.conversation.copy(id = sessionId))
        conversationFlow.value = reduced.conversation
    }

    private fun fold(event: LlmStreamEvent) {
        foldWith(ConversationReducer.reduce(reduced, event))
        reducedStatus = AgentStateReducer.reduce(reducedStatus, event)
        publishStatus(reducedStatus.status, event)
    }

    /**
     * 状态发射门：换态/终态/新段直通，同态纯文本增长按 [STATUS_TEXT_THROTTLE_MS] 节流。
     *
     * 节流只是少发射中间值：每次换态/终态都用 reducer 手里的全量值直通，
     * 所以订阅方最终收敛无损（StateFlow 的 conflated 语义）。
     */
    private fun publishStatus(state: AgentState, event: LlmStreamEvent) {
        val direct = when {
            state::class != statusFlow.value::class -> true
            event is LlmStreamEvent.Completed || event is LlmStreamEvent.Error -> true
            event is LlmStreamEvent.TextDelta && event.isSegmentStart -> true
            else -> false
        }
        if (direct) {
            statusFlow.value = state
            lastStatusEmitMs = nowMs()
            return
        }
        if (state is AgentState.Generating || state is AgentState.Thinking) {
            if (nowMs() - lastStatusEmitMs >= STATUS_TEXT_THROTTLE_MS) {
                statusFlow.value = state
                lastStatusEmitMs = nowMs()
            }
            return
        }
        statusFlow.value = state
        lastStatusEmitMs = nowMs()
    }

    /** 节流时钟（JVM 可测；500ms 粒度下 NTP 跳变可忽略），仅供 [publishStatus]/[emitStatus] 用。 */
    private fun nowMs(): Long = System.currentTimeMillis()

    /** 状态机外事件（发起/停止/打断/重置）：换态直通，不走文本节流。 */
    private fun emitStatus(state: AgentState) {
        statusFlow.value = state
        lastStatusEmitMs = nowMs()
    }

    internal fun foldForTest(event: LlmStreamEvent) {
        fold(event)
    }

    private fun foldWith(reduced: Reduced) {
        this.reduced = reduced
        conversationFlow.value = reduced.conversation
    }

    /** Pending 草稿项 → 落盘 → 归约成 Ready；失败即移除该项（消费方从状态看到它消失）。 */
    private suspend fun ingest(pending: DraftImage.Pending) {
        val ingested = runCatching { LLMController.ingestUserImage(pending.uri) }.getOrNull()
        updateDraft { current ->
            val rest = current.images.filterNot { it is DraftImage.Pending && it.uri == pending.uri }
            val images = if (ingested == null) {
                rest
            } else {
                rest + DraftImage.Ready(Attachment(path = ingested.path, mimeType = USER_IMAGE_MIME))
            }
            current.copy(images = images)
        }
    }
}
