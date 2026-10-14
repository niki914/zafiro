package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.ContextUsage
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.conversation.ForkResult
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.business.agent.turnIdAt
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun fixture(
    store: FakeHomeConversationStore = FakeHomeConversationStore(),
    configDependencies: HomeChatConfigDependencies = HomeChatConfigDependencies(
        loadConfigs = { LlmConfigsDocument() },
    ),
): Fixture {
    val agent = FakeHomeAgent(store)
    val agentFlow = MutableStateFlow<Agent>(agent)
    installService<AgentManager>(object : AgentManager {
        override val main: Agent get() = agentFlow.value
        override val mainFlow: StateFlow<Agent> = agentFlow.asStateFlow()
        override suspend fun switchTo(conversationId: String): Agent {
            agent.load(ConversationId(conversationId))
            agentFlow.value = agent
            return agent
        }

        override fun startNewConversation(): Agent {
            agent.mutableDraft.value = Draft()
            agent.mutableConversation.value = Conversation()
            agent.mutableStatus.value = AgentState.Idle()
            agentFlow.value = agent
            return agent
        }

        override val activeStates: StateFlow<Map<String, AgentState>> = MutableStateFlow(emptyMap())
        override fun createTaskAgent(isPersistent: Boolean): Agent = agent
        override fun destroy(conversationId: String) {}
        override val control: AgentControl = agent
    })
    return Fixture(
        store = store,
        agent = agent,
        vm = HomeChatViewModel(
            conversations = store,
            textPacer = TextPacer(delayFn = {}),
            thinkingPacer = TextPacer(delayFn = {}),
            configDependencies = configDependencies,
        ),
    )
}

internal data class Fixture(
    val store: FakeHomeConversationStore,
    val agent: FakeHomeAgent,
    val vm: HomeChatViewModel,
)

internal class FakeHomeAgent(private val store: FakeHomeConversationStore) : Agent {
    val mutableDraft = MutableStateFlow(Draft())
    val mutableConversation = MutableStateFlow(Conversation())
    val mutableStatus = MutableStateFlow<AgentState>(AgentState.Idle())
    val mutableContextUsage = MutableStateFlow<ContextUsage?>(null)
    override val draft: StateFlow<Draft> = mutableDraft.asStateFlow()
    override val conversation: StateFlow<Conversation> = mutableConversation.asStateFlow()
    override val contextUsage: StateFlow<ContextUsage?> = mutableContextUsage.asStateFlow()
    override val status: StateFlow<AgentState> = mutableStatus.asStateFlow()

    var streamResult = TurnStart.Started

    /** 发起回合时写入的正文块文本；null = 不写（思考块下标从 0 起的空回合）。 */
    var streamText: String? = "answer"
    var stopCount = 0
    var discardCount = 0
    var lastLoadedId: ConversationId? = null
    val sentDrafts = mutableListOf<Draft>()
    val sentImages = mutableListOf<List<ContentBlock.Image>>()

    override fun updateDraft(transform: (Draft) -> Draft) {
        mutableDraft.value = transform(mutableDraft.value).let { draft ->
            draft.copy(images = draft.images.map { image ->
                if (image is DraftImage.Pending) {
                    DraftImage.Ready(
                        Attachment(
                            "/ingested/image-${image.uri.substringAfterLast('/')}.jpg",
                            "image/jpeg",
                        ),
                    )
                } else image
            })
        }
    }

    override fun clearDraft() {
        mutableDraft.value = Draft()
    }

    override fun stream(): TurnStart {
        if (streamResult != TurnStart.Started) return streamResult
        val draft = mutableDraft.value
        sentDrafts += draft
        val images = draft.images.filterIsInstance<DraftImage.Ready>()
            .map { ContentBlock.Image(it.attachment.path, it.attachment.mimeType ?: "image/jpeg") }
        sentImages += images
        mutableDraft.value = Draft()
        val turns = mutableConversation.value.turns
        val id = mutableConversation.value.id ?: ConversationId("session-${sentDrafts.size}")
        val turnIndex = turns.size
        mutableConversation.value = Conversation(
            id = id,
            turns = turns + ConversationTurn(
                id = turnIdAt(turnIndex),
                userText = draft.text,
                images = draft.images.filterIsInstance<DraftImage.Ready>().map { it.attachment },
                blocks = streamText?.let { listOf(TurnBlock.Text("t$turnIndex:0", it)) }.orEmpty(),
            ),
        )
        mutableStatus.value = AgentState.Idle(TurnOutcome.Completed)
        store.recordNewConversation(id.value, draft.text)
        return TurnStart.Started
    }

    override fun stop() {
        stopCount++
        mutableStatus.value = AgentState.Idle()
    }

    override fun discard() {
        discardCount++
        mutableDraft.value = Draft()
        mutableConversation.value = Conversation()
        mutableStatus.value = AgentState.Idle()
    }

    var releaseCount = 0
    override fun release() {
        releaseCount++
    }

    var boundConfigId: String = ""
    override fun bindConfig(configId: String) {
        boundConfigId = configId
    }

    override suspend fun load(id: ConversationId) {
        lastLoadedId = id
        val record = store.getConversation(id.value)
        mutableConversation.value =
            record?.let { ConversationFormatter.toConversation(it.snapshot) }
                ?: Conversation(id = id)
        mutableStatus.value = AgentState.Idle()
        store.setLastOpenedConversationId(id.value)
    }

    fun publishConversation(conversation: Conversation) {
        mutableConversation.value = conversation
    }

    /** 在本轮回合末尾追加一个思考块（模拟流式新块首发）。 */
    fun publishThinking(text: String, isComplete: Boolean) {
        val conversation = mutableConversation.value
        val turnIndex = conversation.turns.lastIndex
        val turn = conversation.turns[turnIndex]
        val blockIndex = turn.blocks.size
        mutableConversation.value = conversation.copy(
            turns = conversation.turns.toMutableList().also { turns ->
                turns[turnIndex] = turn.copy(
                    blocks = turn.blocks + TurnBlock.Thinking(
                        id = "t$turnIndex:$blockIndex",
                        text = text,
                        isComplete = isComplete,
                    ),
                )
            },
        )
    }

    /** 改写本轮回合里的已有思考块（模拟流式续接与结束）。 */
    fun updateThinking(blockIndex: Int, text: String, isComplete: Boolean) {
        val conversation = mutableConversation.value
        val turnIndex = conversation.turns.lastIndex
        val turn = conversation.turns[turnIndex]
        mutableConversation.value = conversation.copy(
            turns = conversation.turns.toMutableList().also { turns ->
                turns[turnIndex] = turn.copy(
                    blocks = turn.blocks.toMutableList().also { blocks ->
                        val block = blocks[blockIndex] as TurnBlock.Thinking
                        blocks[blockIndex] = block.copy(text = text, isComplete = isComplete)
                    },
                )
            },
        )
    }

    fun publishStatus(status: AgentState) {
        mutableStatus.value = status
    }

    override fun addApprover(approver: Approver) {
        registeredApprover = approver
    }

    override fun removeApprover(approver: Approver) {
        if (registeredApprover === approver) registeredApprover = null
    }

    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision =
        error("unused in HomeChatViewModelTest")

    /** ViewModel 注册进来的前台审批来源（本类不代它结算）。 */
    var registeredApprover: Approver? = null
}

internal class FakeHomeConversationStore(
    private val restoreOnStartup: Boolean = false,
) : HomeConversationStore {
    private val records = linkedMapOf<String, ConversationRecord>()
    private var lastOpened = ""
    private var draft = ""

    override suspend fun lastOpenedConversationId() = lastOpened
    override suspend fun setLastOpenedConversationId(value: String) {
        lastOpened = value
    }

    override suspend fun loadLastConversationOnStartup() = restoreOnStartup
    override suspend fun getConversation(id: String) = records[id]
    val boundConfigs = linkedMapOf<String, String>()
    var currentActiveConfigId = "active-default"

    override suspend fun draftText(): String = draft
    override suspend fun setDraftText(value: String) {
        draft = value
    }

    override suspend fun bindConfig(conversationId: String) {
        if (records[conversationId]?.configId?.isNotBlank() == true) return
        boundConfigs[conversationId] = currentActiveConfigId
        records[conversationId]?.let {
            records[conversationId] = it.copy(configId = currentActiveConfigId)
        }
    }

    override suspend fun updateConfigId(conversationId: String, configId: String) {
        boundConfigs[conversationId] = configId
        records[conversationId]?.let {
            records[conversationId] = it.copy(configId = configId)
        }
    }

    override suspend fun deleteConversation(id: String) {
        // 与仓储一致：删除一个会话 = 删掉它的整个分支组
        val rootId = records[id]?.summary?.rootId ?: id
        records.keys.filter { key ->
            val summary = records[key]?.summary
            key == rootId || summary?.rootId == rootId
        }.forEach { records.remove(it) }
    }

    override suspend fun branchGroup(conversationId: String): List<ConversationSummary> {
        val rootId = records[conversationId]?.summary?.rootId ?: return emptyList()
        val group = records.values
            .map { it.summary }
            .filter { it.rootId == rootId }
            .sortedBy { it.createdAt }
        return if (group.size <= 1) emptyList() else group
    }

    override suspend fun branchGroupIds(conversationId: String): List<String> {
        val rootId = records[conversationId]?.summary?.rootId ?: return listOf(conversationId)
        return records.values
            .map { it.summary }
            .filter { it.rootId == rootId }
            .sortedBy { it.createdAt }
            .map { it.id }
    }

    override suspend fun latestBranchId(rootId: String): String? {
        return records.values
            .map { it.summary }
            .filter { it.rootId == rootId }
            .maxByOrNull { it.updatedAt }
            ?.id
    }

    override suspend fun clearUnread(conversationId: String) {}

    fun recordNewConversation(id: String, title: String) {
        if (id in records) return
        records[id] = ConversationRecord(
            summary = ConversationSummary(
                id,
                title,
                false,
                records.size.toLong(),
                records.size.toLong(),
                title,
                0,
            ),
            draftText = "",
            snapshot = SessionSnapshot(id, null, 1, emptyList()),
        )
        lastOpened = id
    }

    suspend fun createRecord(id: String, title: String, configId: String = "") {
        records[id] = ConversationRecord(
            summary = ConversationSummary(
                id = id,
                title = ConversationFormatter.titleFromFirstInput(title),
                titleEdited = false,
                createdAt = records.size.toLong(),
                updatedAt = records.size.toLong(),
                lastMessagePreview = title,
                turnCount = 0,
            ),
            draftText = "",
            snapshot = SessionSnapshot(id = id, leafId = null, version = 1, entries = emptyList()),
            configId = configId,
        )
    }

    fun setSnapshot(id: String, snapshot: SessionSnapshot) {
        records[id]?.let { records[id] = it.copy(snapshot = snapshot.copy(id = id)) }
    }

    override suspend fun forkAtTurn(sourceId: String, turnIndex: Int, kind: ForkKind): ForkResult? {
        val source = records[sourceId] ?: return null
        val projected =
            ConversationFormatter.projectLeaf(source.snapshot.entries, source.snapshot.leafId)
        val userEntryIndex = projected.withIndex()
            .filter { (_, entry) -> entry.message is Message.User }
            .let { users -> users.getOrNull(turnIndex)?.index ?: -1 }
        if (userEntryIndex < 0) return null
        val keepEntryCount = when (kind) {
            ForkKind.Fork -> (userEntryIndex + 1 until projected.size)
                .firstOrNull { projected[it].message is Message.User }
                ?: projected.size

            ForkKind.Regenerate, ForkKind.Rewind -> userEntryIndex
        }
        val entries = projected.take(keepEntryCount)
        val prefix = when (kind) {
            ForkKind.Fork -> "Fork · "
            ForkKind.Regenerate -> "Regenerate · "
            ForkKind.Rewind -> "Rewind · "
        }
        val id = "fork-${records.size}"
        // Regenerate 聚成同一组：共享原始标题并记录父节点和分叉轮次；Fork/Rewind 另起一线
        val isRegenerate = kind == ForkKind.Regenerate
        val rootId = if (isRegenerate) source.summary.rootId else id
        val parentId = if (isRegenerate) source.summary.id else null
        val forkTurn = if (isRegenerate) turnIndex else null
        records[id] = source.copy(
            summary = source.summary.copy(
                id = id,
                title = if (isRegenerate) source.summary.title else prefix + source.summary.title,
                rootId = rootId,
                parentId = parentId,
                forkTurn = forkTurn,
            ),
            draftText = "",
            snapshot = SessionSnapshot(id, entries.lastOrNull()?.id, 1, entries),
        )
        if (kind == ForkKind.Fork) return ForkResult(id)
        val userMessage = projected[userEntryIndex].message as Message.User
        return ForkResult(
            newConversationId = id,
            promptText = userMessage.content
                .filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
            images = userMessage.content
                .filterIsInstance<ContentBlock.Image>().map { Attachment(it.path, it.mimeType) },
        )
    }
}

internal fun snapshotOf(vararg messages: Message): SessionSnapshot {
    var parent: String? = null
    val entries = messages.mapIndexed { index, message ->
        ConversationEntry("entry-$index", parent, index.toLong(), message).also { parent = it.id }
    }
    return SessionSnapshot("fixture", entries.lastOrNull()?.id, 1, entries)
}
