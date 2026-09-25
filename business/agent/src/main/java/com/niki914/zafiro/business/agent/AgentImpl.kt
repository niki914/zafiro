package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private const val LOG_TAG = "niki914_nexus_AgentImpl"

    /** ingest 管线统一转码，用户附件按 jpeg 声明。 */
    private const val USER_IMAGE_MIME = "image/jpeg"

    private val draftFlow = MutableStateFlow(Draft())
    private val conversationFlow = MutableStateFlow(Conversation())

    private val statusFlow = MutableStateFlow(AgentStatus())

    private val roundActive = MutableStateFlow(false)
    private var streamJob: Job? = null
    private var roundToken = 0

    /** 归约器辅助态：保留跨事件的思考槽与待补全工具占位项。 */
    private var reduced = Reduced(Conversation())
    private var reducedStatus = AgentStatusReducer.reset()

    override val conversation: StateFlow<Conversation> = conversationFlow.asStateFlow()
    override val draft: StateFlow<Draft> = draftFlow.asStateFlow()
    override val status: StateFlow<AgentStatus> = statusFlow.asStateFlow()

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
        if (draft.text.isBlank() && draft.images.isEmpty()) return TurnStart.DraftEmpty
        if (!roundActive.compareAndSet(expect = false, update = true)) return TurnStart.Busy

        val query = draft.text
        val attachments = draft.images.mapNotNull { image ->
            when (image) {
                is DraftImage.Pending -> null
                is DraftImage.Ready -> image.attachment
            }
        }
        val images = attachments.map {
            ContentBlock.Image(it.path, it.mimeType ?: USER_IMAGE_MIME)
        }
        // 发起即清空草稿：写入与清空在同一次归约里，覆盖窗口只有一帧
        draftFlow.value = Draft()
        reducedStatus = AgentStatusReducer.startRound(query)
        statusFlow.value = reducedStatus.status
        foldWith(ConversationReducer.startTurn(conversationFlow.value, query, attachments))

        val token = ++roundToken
        streamJob = scope.launch {
            try {
                val conversationId = ensureConversation(query)
                store().saveDraft(conversationId, "")
                Logger.i(LOG_TAG, "round started conversationId=${conversationId.value} queryLength=${query.length}")
                LLMController.stream(query = query, images = images).collect { event ->
                    fold(event)
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Logger.e(LOG_TAG, "round failed errorType=${throwable::class.simpleName} message=${throwable.message}")
                val message = throwable.message?.trim()?.takeIf(String::isNotEmpty)
                if (message != null) fold(LlmStreamEvent.Error(message = message))
                else {
                    reducedStatus = AgentStatusReducer.interrupt(reducedStatus)
                    statusFlow.value = reducedStatus.status
                }
            } finally {
                if (roundToken == token) {
                    if (statusFlow.value.phase != AgentPhase.Idle) {
                        reducedStatus = AgentStatusReducer.interrupt(reducedStatus)
                        statusFlow.value = reducedStatus.status
                    }
                    streamJob = null
                    roundActive.value = false
                }
            }
        }
        return TurnStart.Started
    }

    override fun stop() {
        if (roundActive.value) {
            conversationFlow.value = ConversationReducer.interrupt(conversationFlow.value)
            reducedStatus = AgentStatusReducer.interrupt(reducedStatus)
            statusFlow.value = reducedStatus.status
        }
        releaseRound()
        scope.launch {
            LLMController.stopCurrentRound()
        }
    }

    override fun discard() {
        releaseRound()
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStatusReducer.reset()
        statusFlow.value = reducedStatus.status
        scope.launch {
            // 调用点不再维持顺序：先停后关在实现内部（OKIA §8.7 #5）
            LLMController.stopCurrentRound()
            LLMController.resetConversation()
        }
    }

    override suspend fun load(id: ConversationId) {
        releaseRound()
        reducedStatus = AgentStatusReducer.reset()
        statusFlow.value = reducedStatus.status
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

    override fun addApprover(approver: Approver): Unit = error("approver 注册在 M6")

    override fun removeApprover(approver: Approver): Unit = error("approver 注册在 M6")

    /** 单测复位：进程内单例状态跨用例保留（同 `LLMController.resetForTest`）。 */
    internal fun clearForTest() {
        releaseRound()
        reduced = Reduced(Conversation())
        conversationFlow.value = Conversation()
        draftFlow.value = Draft()
        reducedStatus = AgentStatusReducer.reset()
        statusFlow.value = reducedStatus.status
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
        conversationFlow.value = conversationFlow.value.copy(id = sessionId)
        return sessionId
    }

    private fun fold(event: LlmStreamEvent) {
        foldWith(ConversationReducer.reduce(reduced, event))
        reducedStatus = AgentStatusReducer.reduce(reducedStatus, event)
        statusFlow.value = reducedStatus.status
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
