package com.niki914.zafiro.agent

import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.okia.message.ContentBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// 存废：阶段 5 删除（委托实现：内部转发到 LLMController；架空完成后删除）

/**
 * `Agent` 的委托实现：命令面走新接口，执行走遗留 `LLMController`。
 *
 * 本层真做的只有三条命令与草稿，其余成员是 stub（见 M4 / 层 3）。
 * 草稿的文本与图片由 `HomeChatViewModel` 经 [updateDraft] 镜像进来；
 * [stream] 在同一次发起里消费掉草稿（发起即清空）。
 *
 * 门禁是自有 [roundActive]，与宿主 `AgentRuntimeService.activeTurn` 的 CAS
 * 互不可见（已知偏差，M5 合并门禁）。
 */
object LlmAgent : Agent {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val draftFlow = MutableStateFlow(Draft())
    private val conversationStub = MutableStateFlow(Conversation())
    private val statusStub = MutableStateFlow(AgentStatus())

    private val roundActive = MutableStateFlow(false)
    private var streamJob: Job? = null

    private var eventChannel = Channel<LlmStreamEvent>(Channel.UNLIMITED)

    /**
     * 非契约的临时事件通道，供 `HomeChatRuntime` 折叠用。M3e 删除。
     *
     * 每回合一个新 channel，回合结束时关闭：消费方的 `collect` 随回合返回，
     * 与旧冷流的语义一致。不关闭会让上一轮的 collector 永不返回，新一轮的事件
     * 被它取走并折叠进错误的回合。单消费者，`receiveAsFlow` 表达「谁发起谁收」。
     */
    val events: Flow<LlmStreamEvent> get() = eventChannel.receiveAsFlow()

    override val conversation: StateFlow<Conversation> = conversationStub.asStateFlow()
    override val draft: StateFlow<Draft> = draftFlow.asStateFlow()
    override val status: StateFlow<AgentStatus> = statusStub.asStateFlow()

    override fun updateDraft(transform: (Draft) -> Draft) {
        draftFlow.value = transform(draftFlow.value)
    }

    override fun clearDraft() {
        draftFlow.value = Draft()
    }

    override fun stream(): TurnStart {
        val draft = draftFlow.value
        if (draft.text.isBlank() && draft.images.isEmpty()) {
            return TurnStart.DraftEmpty
        }
        if (!roundActive.compareAndSet(expect = false, update = true)) {
            return TurnStart.Busy
        }
        val query = draft.text
        val images = draft.images.mapNotNull { image ->
            when (image) {
                is DraftImage.Pending -> null
                is DraftImage.Ready -> ContentBlock.Image(
                    image.attachment.path,
                    image.attachment.mimeType ?: "image/jpeg",
                )
            }
        }
        draftFlow.value = Draft()
        val channel = Channel<LlmStreamEvent>(Channel.UNLIMITED)
        eventChannel = channel
        streamJob = scope.launch {
            try {
                LLMController.stream(query = query, images = images).collect { event ->
                    channel.send(event)
                }
            } finally {
                // 关闭让消费方的 collect 随回合结束返回（旧冷流语义）。
                channel.close()
                roundActive.value = false
            }
        }
        return TurnStart.Started
    }

    override fun stop() {
        streamJob?.cancel()
        streamJob = null
        scope.launch {
            LLMController.stopCurrentRound()
        }
    }

    override fun discard() {
        streamJob?.cancel()
        streamJob = null
        scope.launch {
            // 调用点不再维持顺序：先停后关在实现内部（OKIA §8.7 #5）。
            LLMController.stopCurrentRound()
            LLMController.resetConversation()
        }
    }

    override suspend fun load(id: ConversationId): Unit =
        error("not in layer 1")

    override fun addApprover(approver: Approver): Unit =
        error("not in layer 1")

    override fun removeApprover(approver: Approver): Unit =
        error("not in layer 1")

    /** 单测复位：进程内单例状态跨用例保留。 */
    internal fun clearForTest() {
        streamJob?.cancel()
        streamJob = null
        eventChannel.close()
        eventChannel = Channel(Channel.UNLIMITED)
        draftFlow.value = Draft()
        roundActive.value = false
    }
}
