package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.business.agent.turnIdAt
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun fixture(
    store: FakeHomeConversationStore = FakeHomeConversationStore(),
    history: List<Message> = emptyList(),
): Fixture {
    val agent = FakeHomeAgent(store)
    installService<Agent>(agent)
    return Fixture(
        store = store,
        agent = agent,
        vm = HomeChatViewModel(
            conversations = store,
            textPacer = TextPacer(delayFn = {}),
            thinkingPacer = TextPacer(delayFn = {}),
            historySnapshot_Tmp = { history },
        ),
    )
}

internal data class Fixture(
    val store: FakeHomeConversationStore,
    val agent: FakeHomeAgent,
    val vm: HomeChatViewModel,
)

internal class FakeHomeAgent(private val store: FakeHomeConversationStore) : Agent {
    private val mutableDraft = MutableStateFlow(Draft())
    private val mutableConversation = MutableStateFlow(Conversation())
    private val mutableStatus = MutableStateFlow(AgentStatus())
    override val draft: StateFlow<Draft> = mutableDraft.asStateFlow()
    override val conversation: StateFlow<Conversation> = mutableConversation.asStateFlow()
    override val status: StateFlow<AgentStatus> = mutableStatus.asStateFlow()

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
                attachments = draft.images.filterIsInstance<DraftImage.Ready>().map { it.attachment },
                blocks = streamText?.let { listOf(TurnBlock.Text("t$turnIndex:0", it)) }.orEmpty(),
            ),
        )
        mutableStatus.value = AgentStatus(outcome = com.niki914.zafiro.api.model.TurnOutcome.Completed)
        store.recordNewConversation(id.value, draft.text)
        return TurnStart.Started
    }

    override fun stop() {
        stopCount++
        mutableStatus.value = AgentStatus()
    }

    override fun discard() {
        discardCount++
        mutableDraft.value = Draft()
        mutableConversation.value = Conversation()
        mutableStatus.value = AgentStatus()
    }

    override suspend fun load(id: ConversationId) {
        lastLoadedId = id
        val record = store.getConversation(id.value)
        mutableConversation.value = record?.let { ConversationFormatter.toConversation(it.snapshot) }
            ?: Conversation(id = id)
        mutableDraft.value = record?.let { Draft(it.draftText) } ?: Draft()
        mutableStatus.value = AgentStatus()
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

    fun publishStatus(status: AgentStatus) {
        mutableStatus.value = status
    }

    override fun addApprover(approver: Approver) = error("unused in HomeChatViewModelTest")
    override fun removeApprover(approver: Approver) = error("unused in HomeChatViewModelTest")
}

internal class FakeHomeConversationStore(
    private val restoreOnStartup: Boolean = false,
) : HomeConversationStore {
    private val records = linkedMapOf<String, ConversationRecord>()
    private var lastOpened = ""

    override suspend fun lastOpenedConversationId() = lastOpened
    override suspend fun setLastOpenedConversationId(value: String) { lastOpened = value }
    override suspend fun loadLastConversationOnStartup() = restoreOnStartup
    override suspend fun getConversation(id: String) = records[id]
    override suspend fun updateDraft(conversationId: String, draftText: String) {
        records[conversationId]?.let { records[conversationId] = it.copy(draftText = draftText) }
    }
    override suspend fun deleteConversation(id: String) { records.remove(id) }

    fun recordNewConversation(id: String, title: String) {
        if (id in records) return
        records[id] = ConversationRecord(
            summary = ConversationSummary(id, title, false, records.size.toLong(), records.size.toLong(), title, 0),
            draftText = "",
            snapshot = SessionSnapshot(id, null, 1, emptyList()),
        )
        lastOpened = id
    }

    suspend fun createRecord(id: String, title: String) {
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
        )
    }

    fun setSnapshot(id: String, snapshot: SessionSnapshot) {
        records[id]?.let { records[id] = it.copy(snapshot = snapshot.copy(id = id)) }
    }

    override suspend fun forkConversation(sourceId: String, keepEntryCount: Int, kind: ForkKind): String {
        val source = records.getValue(sourceId)
        val entries = ConversationFormatter.projectLeaf(source.snapshot.entries, source.snapshot.leafId)
            .take(keepEntryCount)
        val prefix = when (kind) {
            ForkKind.Fork -> "Fork · "
            ForkKind.Regenerate -> "Regenerate · "
            ForkKind.Rewind -> "Rewind · "
        }
        val id = "fork-${records.size}"
        records[id] = source.copy(
            summary = source.summary.copy(id = id, title = prefix + source.summary.title),
            draftText = "",
            snapshot = SessionSnapshot(id, entries.lastOrNull()?.id, 1, entries),
        )
        return id
    }
}

internal fun snapshotOf(vararg messages: Message): SessionSnapshot {
    var parent: String? = null
    val entries = messages.mapIndexed { index, message ->
        ConversationEntry("entry-$index", parent, index.toLong(), message).also { parent = it.id }
    }
    return SessionSnapshot("fixture", entries.lastOrNull()?.id, 1, entries)
}
