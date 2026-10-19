package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.contextTokens
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.ContextUsage
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.business.agent.AgentImpl.Companion.STATUS_TEXT_THROTTLE_MS
import com.niki914.zafiro.chat.AgentSessionEngine
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.niki914.okia.conversation.Conversation as OkiaConversation

/**
 * `Agent` 的实现类。
 *
 * 每个实例持有一个独立的底层会话引擎 [engine]，具备完全独立的草稿、会话树、执行状态。
 * - [isPersistent] 为 true 时（主前台对话）：首轮自动建档并写入 Room，支持加载历史会话；
 * - [isPersistent] 为 false 时（瞬态任务对话）：纯内存运行，不写 Room 历史，不污染本地数据。
 */
class AgentImpl(
    val engine: AgentSessionEngine = AgentSessionEngine(),
    val isPersistent: Boolean = true,
) : Agent {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e(LOG_TAG, "Uncaught exception in Agent scope: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

    /** 持久化端口：仅当 isPersistent == true 时由 app 侧提供并在组合根 install。 */
    private fun store(): ConversationStore_Tmp = requireService()

    private companion object {
        const val LOG_TAG = "niki914_zafiro_AgentImpl"
        const val STATUS_TEXT_THROTTLE_MS = 250L
        const val USER_IMAGE_MIME = "image/jpeg"
    }

    private val draftFlow = MutableStateFlow(Draft())
    private val conversationFlow = MutableStateFlow(Conversation())
    private val statusFlow = MutableStateFlow<AgentState>(AgentState.Idle())
    private val approvalFlow = MutableStateFlow<ApprovalRequest?>(null)

    private val roundActive = MutableStateFlow(false)
    internal val isRoundActive: Boolean get() = roundActive.value
    private var streamJob: Job? = null
    private var roundToken = 0
    private var lastStatusEmitMs = 0L

    private var reduced = Reduced(Conversation())
    private var reducedStatus = AgentStateReducer.reset()

    override val conversation: StateFlow<Conversation> = conversationFlow.asStateFlow()
    override val draft: StateFlow<Draft> = draftFlow.asStateFlow()

    /**
     * 分子取 [conversation]（回合结束/载入/重置都会改它），分母取引擎当前生效配置的窗口。
     * 两者任一变化即重算，不需要在 [stream]/[load]/[bindConfig]/[discard] 里各自记账，
     * 也不会漏掉 [clearForTest]。
     */
    override val contextUsage: StateFlow<ContextUsage?> =
        combine(conversationFlow, engine.contextWindowTokens) { conversation, windowTokens ->
            conversation.contextTokens?.let { usedTokens ->
                ContextUsage(usedTokens = usedTokens, windowTokens = windowTokens)
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override val status: StateFlow<AgentState> =
        combine(statusFlow, approvalFlow) { engineState, pending ->
            pending?.let { AgentState.WaitingApproval(it) } ?: engineState
        }.stateIn(scope, SharingStarted.Eagerly, AgentState.Idle())

    private val approvalMutex = Mutex()

    init {
        engine.agentControlProvider = { this }
        scope.launch {
            draftFlow.collect { draft ->
                for (pending in draft.images.filterIsInstance<DraftImage.Pending>()) ingest(pending)
            }
        }
        if (isPersistent) {
            findService<ConversationStore_Tmp>()?.track(
                agentScope = scope,
                source = engine.currentConversation,
                status = status,
                conversationId = { conversation.value.id?.value },
            )
        }
    }

    override fun updateDraft(transform: (Draft) -> Draft) {
        draftFlow.value = transform(draftFlow.value)
    }

    override fun clearDraft() {
        draftFlow.value = Draft()
    }

    override fun warmup() {
        scope.launch {
            runCatchingCancellable {
                engine.warmup()
            }.onFailure { throwable ->
                Logger.d(LOG_TAG, "agent warmup skipped: ${throwable.message}")
            }
        }
    }

    override fun stream(): TurnStart {
        val draft = draftFlow.value
        if (draft.text.isBlank() && draft.images.isEmpty() && draft.files.isEmpty()) {
            return TurnStart.DraftEmpty
        }
        if (!roundActive.compareAndSet(expect = false, update = true)) return TurnStart.Busy

        val query = draft.text
        val isCompactCommand = draft.images.isEmpty() &&
                draft.files.isEmpty() &&
                query.trim().equals("/compact", ignoreCase = true)

        if (isCompactCommand) {
            draftFlow.value = Draft()
            val token = ++roundToken
            reducedStatus = AgentStateReducer.startRound()
            emitStatus(reducedStatus.status)
            val previousConversation = conversationFlow.value
            foldWith(ConversationReducer.addCompactingDivider(previousConversation))
            streamJob = scope.launch {
                try {
                    runCatchingCancellable {
                        ensureConversation(query)
                        val compacted = engine.compact(force = true)
                        if (compacted) {
                            foldWith(ConversationReducer.markCompacted(conversationFlow.value))
                        } else {
                            foldWith(Reduced(previousConversation))
                        }
                    }.onFailure { throwable ->
                        foldWith(Reduced(previousConversation))
                        Logger.e(LOG_TAG, "compact round failed", throwable)
                    }
                } catch (e: CancellationException) {
                    foldWith(Reduced(previousConversation))
                    throw e
                } finally {
                    if (roundToken == token && statusFlow.value !is AgentState.Stopping) {
                        reducedStatus = AgentStateReducer.reset()
                        emitStatus(reducedStatus.status)
                        streamJob = null
                        roundActive.value = false
                    }
                }
            }
            return TurnStart.Started
        }

        val images = draft.images.mapNotNull { image ->
            when (image) {
                is DraftImage.Pending -> null
                is DraftImage.Ready -> image.attachment
            }
        }
        val files = draft.files
        val contentImages = images.map {
            ContentBlock.Image(it.path, it.mimeType ?: USER_IMAGE_MIME)
        }
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.startRound()
        emitStatus(reducedStatus.status)
        foldWith(ConversationReducer.startTurn(conversationFlow.value, query, images, files))

        val token = ++roundToken
        streamJob = scope.launch {
            try {
                runCatchingCancellable {
                    val conversationId = ensureConversation(query)
                    Logger.i(
                        LOG_TAG,
                        "round started conversationId=${conversationId.value} queryLength=${query.length}"
                    )
                    engine.stream(query = query, images = contentImages, files = files)
                        .collect { event ->
                            fold(event)
                        }
                }.onFailure { throwable ->
                    Logger.e(
                        LOG_TAG,
                        "round failed errorType=${throwable::class.simpleName} message=${throwable.message}"
                    )
                    val message = throwable.message?.trim()?.takeIf(String::isNotEmpty)
                    if (message != null) fold(LlmStreamEvent.Error(message = message))
                    else {
                        reducedStatus = AgentStateReducer.interrupt(reducedStatus)
                        emitStatus(reducedStatus.status)
                    }
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

        activeApproval?.result?.complete(ApprovalDecision.Deny)

        // 写回唯一真源 reduced（不是只改派生流）：只写 conversationFlow 的话，之后
        // 任何一条 fold（如流终态守卫那条 Error）都会按旧快照重算整棵树，把刚标记为
        // 打断的工具块复活成转圈。
        foldWith(reduced.copy(conversation = ConversationReducer.interrupt(reduced.conversation)))
        reducedStatus = AgentStateReducer.stopping(reducedStatus)
        emitStatus(reducedStatus.status)

        val currentJob = streamJob
        val token = roundToken
        scope.launch {
            try {
                engine.stopCurrentRound()
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
        engine.bindConfig("")
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.reset()
        emitStatus(reducedStatus.status)
        scope.launch {
            runCatchingCancellable {
                engine.stopCurrentRound()
                engine.resetConversation()
            }.onFailure { t ->
                Logger.w(LOG_TAG, "discard cleanup failed: ${t.message}", t)
            }
        }
    }

    override fun release() {
        releaseRound()
        engine.bindConfig("")
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.reset()
        emitStatus(reducedStatus.status)
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            runCatchingCancellable {
                engine.stopCurrentRound()
                engine.closeSession()
            }.onFailure { t ->
                Logger.w(LOG_TAG, "release session cleanup failed", t)
            }
        }
        scope.cancel()
    }

    override fun bindConfig(configId: String) {
        engine.bindConfig(configId)
    }

    override suspend fun load(id: ConversationId) {
        releaseRound()
        reducedStatus = AgentStateReducer.reset()
        emitStatus(reducedStatus.status)
        engine.stopCurrentRound()
        if (!isPersistent) return
        val stored = store().load(id)
        if (stored == null) {
            Logger.w(LOG_TAG, "load skipped notFound id=${id.value}")
            return
        }
        engine.bindConfig(stored.configId)
        engine.openSession(stored.snapshot)
        store().setLastOpened(id)
        reduced = Reduced(stored.conversation.copy(id = id))
        conversationFlow.value = stored.conversation.copy(id = id)
        Logger.i(LOG_TAG, "loaded id=${id.value} turns=${stored.conversation.turns.size}")
    }

    private val approvers = CopyOnWriteArrayList<Approver>()

    private class ActiveApproval(
        val request: ApprovalRequest,
        val result: CompletableDeferred<ApprovalDecision>,
        val scope: CoroutineScope,
    ) {
        val jobs = ConcurrentHashMap<Approver, Job>()
        val decisions = ConcurrentHashMap<Approver, ApprovalDecision>()

        fun attach(approver: Approver) {
            jobs.computeIfAbsent(approver) { app ->
                scope.launch {
                    runCatchingCancellable {
                        val decision = app.decide(request)
                        decisions[app] = decision
                        if (decision != ApprovalDecision.Abstain) {
                            result.complete(decision)
                        } else {
                            checkAllAbstained()
                        }
                    }.onFailure {
                        decisions[app] = ApprovalDecision.Abstain
                        checkAllAbstained()
                    }
                }
            }
        }

        fun detach(approver: Approver) {
            jobs.remove(approver)?.cancel()
            decisions.remove(approver)
        }

        private fun checkAllAbstained() {
            if (jobs.isNotEmpty() && jobs.keys.all { decisions[it] == ApprovalDecision.Abstain } && !result.isCompleted) {
                result.complete(ApprovalDecision.Deny)
            }
        }
    }

    @Volatile
    private var activeApproval: ActiveApproval? = null

    override fun addApprover(approver: Approver) {
        if (!approvers.contains(approver)) {
            approvers.add(approver)
        }
        activeApproval?.attach(approver)
    }

    override fun removeApprover(approver: Approver) {
        approvers.remove(approver)
        activeApproval?.detach(approver)
    }

    /**
     * 并发询问所有已注册的 Approver，首个非 Abstain 决策胜出。
     * 若当前在后台运行（无 Approver 接入），则挂起等待，直到前台换绑或外部接入裁决。
     */
    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision =
        approvalMutex.withLock {
            coroutineScope {
                val deferred = CompletableDeferred<ApprovalDecision>()
                val approval = ActiveApproval(request, deferred, this)
                activeApproval = approval
                approvalFlow.value = request

                approvers.forEach { approval.attach(it) }

                try {
                    deferred.await()
                } finally {
                    approvalFlow.value = null
                    activeApproval = null
                    approval.jobs.values.forEach { it.cancel() }
                }
            }
        }

    /** 单测复位：进程内单例状态跨用例保留（同 `LLMController.resetForTest`）。 */
    internal fun clearForTest() {
        releaseRound()
        approvers.clear()
        activeApproval = null
        approvalFlow.value = null
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStateReducer.reset()
        statusFlow.value = reducedStatus.status
        lastStatusEmitMs = 0L
        engine.bindConfig("")
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 退役当前回合的门禁：旧回合的 finally 不再改它（token 已推进）。 */
    private fun releaseRound() {
        roundToken++
        roundActive.value = false
        activeApproval?.result?.complete(ApprovalDecision.Deny)
        streamJob?.cancel()
        streamJob = null
    }

    /** 会话树 id 即 Room 会话 id（okia 惰性建实例，首轮发起时建档）。 */
    private suspend fun ensureConversation(firstUserInput: String): ConversationId {
        conversationFlow.value.id?.let { return it }
        val sessionId = ConversationId(engine.ensureSession())
        if (isPersistent) {
            if (store().exists(sessionId)) {
                Logger.i(LOG_TAG, "conversation reused id=${sessionId.value}")
            } else {
                store().create(sessionId, firstUserInput, engine.boundConfigId)
                Logger.i(LOG_TAG, "conversation created id=${sessionId.value}")
            }
            store().setLastOpened(sessionId)
        }
        applySessionId(sessionId)
        return sessionId
    }

    internal fun applySessionId(sessionId: ConversationId) {
        reduced = reduced.copy(conversation = reduced.conversation.copy(id = sessionId))
        conversationFlow.value = reduced.conversation
    }

    private fun fold(event: LlmStreamEvent) {
        var next = ConversationReducer.reduce(reduced, event)
        if (event is LlmStreamEvent.Completed || event is LlmStreamEvent.Error) {
            // 回合结束：换成引擎里的最新占用。引擎快照在发事件前已更新，
            // 此刻取到的一定包含本回合刚提交的 usage。
            next = next.copy(
                conversation = next.conversation.copy(
                    contextTokens = contextTokensOf(engine.currentConversation.value)
                        ?: next.conversation.contextTokens,
                ),
            )
        }
        foldWith(next)
        reducedStatus = AgentStateReducer.reduce(reducedStatus, event)
        publishStatus(reducedStatus.status, event)
    }

    /**
     * 当前上下文占用：live 优先（本轮流式中的 usage 一旦上报即最新），
     * 未上报时回落到历史最后一条带记账的助手响应。
     */
    private fun contextTokensOf(conversation: OkiaConversation?): Long? {
        conversation?.live?.usage?.contextTokens?.let { return it }
        return conversation?.history?.asReversed()?.firstNotNullOfOrNull { entry ->
            (entry.message as? Message.Assistant)?.message?.usage?.contextTokens
        }
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

    /** 节流时钟（JVM 可测；250ms 粒度下 NTP 跳变可忽略），仅供 [publishStatus]/[emitStatus] 用。 */
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
        val ingested = runCatching { engine.ingestUserImage(pending.uri) }.getOrNull()
        updateDraft { current ->
            val rest =
                current.images.filterNot { it is DraftImage.Pending && it.uri == pending.uri }
            val images = if (ingested == null) {
                rest
            } else {
                rest + DraftImage.Ready(
                    Attachment(
                        path = ingested.path,
                        mimeType = USER_IMAGE_MIME
                    )
                )
            }
            current.copy(images = images)
        }
    }
}
