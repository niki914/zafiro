package com.niki914.zafiro.app.ui.model.home

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 对话页的 UI 状态持有者。
 *
 * 内容与执行归 [Agent]：回合列表从 `conversation` 映射，生成中从 `status.phase` 派生，
 * 命令全部转发。本类只持有 UI 本地状态（输入框文本、展开态、操作行、标题、加载中）
 * 与打字机节流。
 *
 * 输入框文本以本类为准、单向推给 `Agent.draft`：再加一跳异步读回会加重已知的输入法
 * 乱序问题（见 `Draft` 的契约注释）。待发送图片是例外——落盘是异步的，只能从
 * `draft` 读回。
 *
 * 草稿文本的落盘按按键节流写 Room，契约明确把这一步留在 app 侧。
 */
class HomeChatViewModel internal constructor(
    private val conversations: HomeConversationStore = com.niki914.zafiro.app.ui.model.home.tmp.DefaultHomeConversationStore,
    // 节流器可注入：单测传 delayFn = {} 把放出节奏与状态机解耦（同 TextPacerTest 的用法）
    private val textPacer: TextPacer = TextPacer(),
    // thinking 与正文在流中交织（thinking → tool → text），坐标系独立，单独实例
    private val thinkingPacer: TextPacer = TextPacer(),
    private val historySnapshot_Tmp: suspend () -> List<Message> = { LLMController.historySnapshot() },
) : ComposeMVIViewModel<HomeChatIntent, HomeChatUiState, Nothing>() {
    private val agent: Agent = requireService()
    private var draftSaveJob: Job? = null
    private var startupRestoreAttempted = false

    /** 正在节流的文本块；块 id 变化 = 新段开始，节流器归零。 */
    private var pacedTextBlockId: String? = null

    /** 正在节流的思考块。 */
    private var pacedThinkingBlockId: String? = null
    private var conversationBeingLoaded: String? = null
    private val seenThinkingKeys = mutableSetOf<String>()

    init {
        observeAgent()
        restoreLastConversationOnStartup()
    }

    override fun initUiState(): HomeChatUiState = HomeChatUiState()

    override suspend fun handleIntent(intent: HomeChatIntent) {
        when (intent) {
            is HomeChatIntent.InputChanged -> onInputChanged(intent.value)
            HomeChatIntent.Send -> sendCurrentInput()
            is HomeChatIntent.ImageAttached -> attachImage(intent.uri)
            is HomeChatIntent.ImageRemoved -> removeImage(intent.id)
            HomeChatIntent.StopGenerating -> stopGenerating()
            HomeChatIntent.NewConversation -> startNewConversation()
            is HomeChatIntent.LoadConversation -> loadConversation(intent.id)
            is HomeChatIntent.DeleteConversation -> deleteConversationNow(intent.id)
            is HomeChatIntent.ToggleToolRun -> toggleToolRun(intent.turnId, intent.runStartIndex)
            is HomeChatIntent.ToggleToolResult -> toggleToolResult(
                intent.turnId, intent.runStartIndex, intent.toolIndex,
            )

            is HomeChatIntent.ToggleThinking -> toggleThinking(intent.turnId, intent.blockIndex)
            is HomeChatIntent.ToggleActionRow -> toggleActionRow(intent.turnId, intent.source)
            is HomeChatIntent.ReGenerateAt -> reGenerateAt(intent.turnId)
            is HomeChatIntent.ForkAt -> forkAt(intent.turnId)
            is HomeChatIntent.RewindAt -> rewindAt(intent.turnId)
        }
    }

    // ── Agent 观察 ──────────────────────────────────────────────────────────

    private fun observeAgent() {
        viewModelScope.launch {
            agent.status.collect { status ->
                updateState { copy(isGenerating = status.phase.isGenerating()) }
            }
        }
        viewModelScope.launch {
            agent.conversation.collectLatest { conversation ->
                if (!currentState.isLoadingConversation) {
                    applyConversation(conversation, animateTail = true)
                }
            }
        }
        viewModelScope.launch {
            agent.draft.collect { draft ->
                if (!currentState.isLoadingConversation) {
                    val pendingImages = draft.images.mapNotNull { it.toHomeImage() }
                    updateState { copy(pendingImages = pendingImages) }
                }
            }
        }
    }

    /**
     * 契约回合 → UI 回合，尾部文本块与思考块按 [TextPacer] 节奏放出。
     *
     * 节流只作用于展示：`Agent.conversation` 始终是全文，这里按块 id 记住已放出的
     * 长度，新发射到来时从已放出处继续追。`StateFlow` 合并中间发射不影响结果，
     * 追赶的终点是最新全文。
     */
    private suspend fun applyConversation(conversation: Conversation, animateTail: Boolean = true) {
        if (conversationBeingLoaded != null && conversation.id?.value != conversationBeingLoaded) return
        val isLoadedConversation = conversation.id?.value == conversationBeingLoaded
        val target = conversation.toHomeTurns().toMutableList()
        val loaded = !animateTail || isLoadedConversation
        if (isLoadedConversation) conversationBeingLoaded = null

        val last = target.lastOrNull()
        var paceBlockIndex = -1
        var paceKind: String? = null
        if (last != null && !loaded) {
            val index = last.blocks.lastIndex
            when (val block = last.blocks.lastOrNull()) {
                is HomeChatBlock.Text -> {
                    val id = "${last.id}:$index"
                    if (id != pacedTextBlockId) {
                        textPacer.reset()
                        pacedTextBlockId = id
                    }
                    paceBlockIndex = index
                    paceKind = "text"
                    target[target.lastIndex] = last.copy(blocks = last.blocks.toMutableList().also {
                        it[index] = block.copy(text = block.text.take(textPacer.released))
                    })
                }

                is HomeChatBlock.Thinking -> {
                    val id = "${last.id}:$index"
                    if (id != pacedThinkingBlockId) {
                        thinkingPacer.reset()
                        pacedThinkingBlockId = id
                    }
                    paceBlockIndex = index
                    paceKind = "thinking"
                    target[target.lastIndex] = last.copy(blocks = last.blocks.toMutableList().also {
                        it[index] = block.copy(text = block.text.take(thinkingPacer.released))
                    })
                }

                else -> Unit
            }
        } else if (loaded) {
            last?.blocks?.lastIndex?.let { index ->
                when (last.blocks.getOrNull(index)) {
                    is HomeChatBlock.Text -> {
                        textPacer.syncReleased((last.blocks[index] as HomeChatBlock.Text).text.length)
                        pacedTextBlockId = "${last.id}:$index"
                    }
                    is HomeChatBlock.Thinking -> {
                        thinkingPacer.syncReleased((last.blocks[index] as HomeChatBlock.Thinking).text.length)
                        pacedThinkingBlockId = "${last.id}:$index"
                    }
                    else -> Unit
                }
            }
        }

        val id = conversation.id?.value
        val title = when {
            id == null -> null
            isLoadedConversation -> currentState.currentConversationTitle
            id != currentState.currentConversationId -> conversation.turns.firstOrNull()?.userText
                ?.let(ConversationFormatter::titleFromFirstInput)?.takeIf { it.isNotBlank() }
            else -> currentState.currentConversationTitle
        }
        updateState {
            copy(
                turns = target,
                currentConversationId = id,
                currentConversationTitle = title,
                conversationVersion = conversationVersion + 1,
            )
        }
        refreshAutoExpandedThinking()

        if (last != null && paceBlockIndex >= 0) {
            val fullText = when (paceKind) {
                "text" -> (last.blocks[paceBlockIndex] as HomeChatBlock.Text).text
                else -> (last.blocks[paceBlockIndex] as HomeChatBlock.Thinking).text
            }
            val pacer = if (paceKind == "text") textPacer else thinkingPacer
            pacer.pace(fullText.length) { _, to ->
                updateTailBlock(last.id, paceBlockIndex, paceKind == "text") { block ->
                    when (block) {
                        is HomeChatBlock.Text -> block.copy(text = fullText.take(to))
                        is HomeChatBlock.Thinking -> block.copy(text = fullText.take(to))
                        else -> block
                    }
                }
            }
        }
    }

    private fun updateTailBlock(
        turnId: Long,
        blockIndex: Int,
        isText: Boolean,
        transform: (HomeChatBlock) -> HomeChatBlock,
    ) {
        updateState {
            val turnIndex = turns.indexOfLast { it.id == turnId }
            if (turnIndex < 0) return@updateState this
            val turn = turns[turnIndex]
            if (turn.blocks.getOrNull(blockIndex)?.let { (it is HomeChatBlock.Text) == isText } != true) {
                return@updateState this
            }
            copy(
                turns = turns.toMutableList().also { list ->
                    list[turnIndex] = turn.copy(blocks = turn.blocks.toMutableList().also {
                        it[blockIndex] = transform(it[blockIndex])
                    })
                },
                conversationVersion = conversationVersion + 1,
            )
        }
    }

    private fun refreshAutoExpandedThinking() {
        val newActiveKey = currentState.turns.flatMap { turn ->
            turn.blocks.mapIndexedNotNull { index, block ->
                if (block is HomeChatBlock.Thinking && seenThinkingKeys.add("${turn.id}_$index") && !block.isComplete) {
                    "${turn.id}_$index"
                } else null
            }
        }.lastOrNull()
        // TODO: Move the auto-scroll decision from Compose's activeThinkingKey check into ViewModel state.
        val activeKeys = currentState.turns.flatMap { turn ->
            turn.blocks.mapIndexedNotNull { index, block ->
                if (block is HomeChatBlock.Thinking && !block.isComplete) "${turn.id}_$index" else null
            }
        }
        updateState {
            copy(
                expandedThinking = if (newActiveKey == null) expandedThinking
                else (expandedThinking - autoExpandedThinking) + newActiveKey,
                autoExpandedThinking = if (newActiveKey == null) autoExpandedThinking else setOf(newActiveKey),
                activeThinkingKey = activeKeys.lastOrNull(),
            )
        }
    }

    // ── 输入 ────────────────────────────────────────────────────────────────

    private fun onInputChanged(value: String) {
        updateState { copy(input = value) }
        agent.updateDraft { it.copy(text = value) }
        val conversationId = agent.conversation.value.id ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(DRAFT_SAVE_DEBOUNCE_MS)
            conversations.updateDraft(conversationId = conversationId.value, draftText = value)
        }
    }

    /** 选图：追加一个待落盘项，实现侧完成后归约成 Ready，图片条从 `draft` 读回。 */
    private fun attachImage(uri: String) {
        if (currentState.isGenerating) return
        agent.updateDraft { draft ->
            if (draft.images.any { it is DraftImage.Pending && it.uri == uri }) draft
            else draft.copy(images = draft.images + DraftImage.Pending(uri))
        }
    }

    private fun removeImage(id: String) {
        agent.updateDraft { draft ->
            draft.copy(images = draft.images.filterNot { image ->
                when (image) {
                    is DraftImage.Pending -> image.uri == id
                    is DraftImage.Ready -> image.attachment.path.hashCode().toString() == id
                }
            })
        }
    }

    private suspend fun sendCurrentInput() {
        agent.updateDraft { it.copy(text = currentState.input.trim()) }
        // stream() is synchronous; don't let it consume Pending images before AgentImpl finishes ingest.
        agent.draft.first { draft -> draft.images.none { it is DraftImage.Pending } }
        updateState { copy(isGenerating = true) }
        val result = agent.stream()
        Logger.i(LOG_TAG, "send result=$result")
        if (result != TurnStart.Started) {
            updateState {
                copy(isGenerating = agent.status.value.phase.isGenerating())
            }
            return
        }
        draftSaveJob?.cancel()
        draftSaveJob = null
        // 输入框以本类为准：发起即清空，不从 draft 读回
        updateState {
            copy(
                input = "",
                pendingImages = emptyList(),
                activeThinkingKey = null,
                autoExpandedThinking = emptySet(),
                // 旧回合的操作行随新回合消失：旧 turn 失去 isLastTurn 后
                // canToggleUserAction 变 false，不清则操作行永远挂着无法收回
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
    }

    private suspend fun stopGenerating() {
        if (!currentState.isGenerating) return
        agent.stop()
        updateState { copy(activeThinkingKey = null) }
        applyConversation(agent.conversation.value, animateTail = false)
    }

    // ── 会话切换 ────────────────────────────────────────────────────────────

    private fun startNewConversation() {
        Logger.d(LOG_TAG, "start new conversation")
        draftSaveJob?.cancel()
        draftSaveJob = null
        resetPacers()
        conversationBeingLoaded = null
        seenThinkingKeys.clear()
        updateState { HomeChatUiState() }
        viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            try {
                agent.discard()
                conversations.setLastOpenedConversationId("")
                Logger.i(
                    LOG_TAG,
                    "new conversation done elapsedMs=${System.currentTimeMillis() - startedAtMs}",
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    private fun restoreLastConversationOnStartup() {
        if (startupRestoreAttempted) return
        startupRestoreAttempted = true
        viewModelScope.launch {
            val shouldRestore = runCatching { conversations.loadLastConversationOnStartup() }
                .getOrDefault(false)
            if (!shouldRestore) {
                Logger.d(LOG_TAG, "restore skipped loadLastConversationOff")
                return@launch
            }
            val conversationId = conversations.lastOpenedConversationId()
            if (conversationId.isBlank()) {
                Logger.d(LOG_TAG, "restore skipped lastOpenedConversationBlank")
                return@launch
            }
            loadConversation(conversationId, restoreDraft = true)
        }
    }

    private suspend fun loadConversation(id: String, restoreDraft: Boolean = false) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "load conversation id=$id started")
        draftSaveJob?.cancel()
        draftSaveJob = null
        resetPacers()
        conversationBeingLoaded = id
        updateState { copy(isLoadingConversation = true) }
        try {
            val record = conversations.getConversation(id)
            if (record == null) {
                conversationBeingLoaded = null
                Logger.w(
                    LOG_TAG,
                    "load conversation id=$id notFound " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}",
                )
                return
            }
            val title = record.summary.title.takeIf { it.isNotBlank() }
            seenThinkingKeys.clear()
            conversationBeingLoaded = id
            agent.load(ConversationId(id))
            if (!restoreDraft) agent.clearDraft()
            updateState {
                copy(
                    turns = emptyList(),
                    input = record.draftText.takeIf { restoreDraft }.orEmpty(),
                    pendingImages = if (restoreDraft) agent.draft.value.images.mapNotNull { it.toHomeImage() } else emptyList(),
                    currentConversationId = id,
                    currentConversationTitle = title,
                ).withClearedTransient()
            }
            applyConversation(agent.conversation.value, animateTail = false)
            Logger.i(
                LOG_TAG,
                "load conversation done id=$id elapsedMs=${System.currentTimeMillis() - startedAtMs}",
            )
        } catch (throwable: Throwable) {
            conversationBeingLoaded = null
            throw throwable
        } finally {
            updateState { copy(isLoadingConversation = false) }
            if (agent.conversation.value.id?.value != id) conversationBeingLoaded = null
        }
    }

    // ── 历史派生操作 ────────────────────────────────────────────────────────
    // TODO(收进 Agent)：reGenerate / fork / rewind 需要按消息条目下标截断，
    // 而契约的 Conversation 把工具结果折成块后丢了条目边界，推不出这个下标。
    // 故此处仍读引擎的 historySnapshot。改走 Room 快照会引入持久化器尚未刷盘的竞态。

    private suspend fun historySnapshot_Tmp(): List<Message> = historySnapshot_Tmp.invoke()

    private fun findUserTurnIndex(history: List<Message>, targetTurnId: Long): Int {
        var userCount = 0L
        for ((index, turn) in history.withIndex()) {
            if (turn is Message.User) {
                if (userCount == targetTurnId) return index
                userCount++
            }
        }
        return -1
    }

    private fun findNextUserIndex(history: List<Message>, startIndex: Int): Int? {
        for (index in startIndex until history.size) {
            if (history[index] is Message.User) return index
        }
        return null
    }

    private fun Message.User.text(): String =
        content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private suspend fun reGenerateAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val history = historySnapshot_Tmp()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val userTurn = history[userIndex] as? Message.User ?: return
        val userText = userTurn.text()
        val userImages = userTurn.content.filterIsInstance<ContentBlock.Image>()
        val newConvId = conversations.forkConversation(currentId, userIndex, ForkKind.Regenerate)
        Logger.i(LOG_TAG, "regenerate fork sourceId=$currentId turnId=$turnId newId=$newConvId")
        loadConversation(newConvId)
        agent.updateDraft { draft ->
            draft.copy(
                text = userText,
                images = userImages.map { DraftImage.Ready(Attachment(it.path, it.mimeType)) },
            )
        }
        if (agent.stream() == TurnStart.Started) {
            updateState { copy(input = "") }
        }
    }

    private suspend fun forkAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val history = historySnapshot_Tmp()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val nextUserIndex = findNextUserIndex(history, userIndex + 1)
        val endIndex = if (nextUserIndex != null) nextUserIndex - 1 else history.lastIndex
        val newConvId = conversations.forkConversation(currentId, endIndex + 1, ForkKind.Fork)
        Logger.i(LOG_TAG, "fork sourceId=$currentId turnId=$turnId endIndex=$endIndex newId=$newConvId")
        loadConversation(newConvId)
    }

    private suspend fun rewindAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val history = historySnapshot_Tmp()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val userTurn = history[userIndex] as? Message.User ?: return
        val newConvId = conversations.forkConversation(currentId, userIndex, ForkKind.Rewind)
        Logger.i(LOG_TAG, "rewind sourceId=$currentId turnId=$turnId newId=$newConvId")
        loadConversation(newConvId)
        val images = userTurn.content.filterIsInstance<ContentBlock.Image>()
        agent.updateDraft { draft ->
            draft.copy(
                text = userTurn.text(),
                images = images.map { DraftImage.Ready(Attachment(it.path, it.mimeType)) },
            )
        }
        updateState {
            copy(
                input = userTurn.text(),
                pendingImages = images.map { it.toHomeImage() },
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
    }

    internal suspend fun deleteConversationNow(id: String) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "delete conversation id=$id started")
        conversations.deleteConversation(id)
        if (id != agent.conversation.value.id?.value) {
            Logger.d(LOG_TAG, "delete conversation id=$id notCurrent skipped")
            return
        }
        draftSaveJob?.cancel()
        draftSaveJob = null
        resetPacers()
        agent.discard()
        conversationBeingLoaded = null
        seenThinkingKeys.clear()
        conversations.setLastOpenedConversationId("")
        updateState { HomeChatUiState() }
        Logger.i(
            LOG_TAG,
            "delete conversation done id=$id elapsedMs=${System.currentTimeMillis() - startedAtMs}",
        )
    }

    // ── 展开态 ──────────────────────────────────────────────────────────────

    private fun toggleToolRun(turnId: Long, runStartIndex: Int) {
        val key = "${turnId}_${runStartIndex}"
        updateState {
            copy(
                expandedToolRuns = if (key in expandedToolRuns) {
                    expandedToolRuns - key
                } else {
                    expandedToolRuns + key
                },
            )
        }
    }

    private fun toggleToolResult(turnId: Long, runStartIndex: Int, toolIndex: Int) {
        val key = "${turnId}_${runStartIndex}_${toolIndex}"
        updateState {
            copy(
                expandedToolResults = if (key in expandedToolResults) {
                    expandedToolResults - key
                } else {
                    expandedToolResults + key
                },
            )
        }
    }

    private fun toggleThinking(turnId: Long, blockIndex: Int) {
        val key = "${turnId}_$blockIndex"
        updateState {
            copy(
                expandedThinking = if (key in expandedThinking) {
                    expandedThinking - key
                } else {
                    expandedThinking + key
                },
                // 用户干预：移出自动展开跟踪，后续新块不再自动收起它
                autoExpandedThinking = autoExpandedThinking - key,
            )
        }
    }

    private fun toggleActionRow(turnId: Long, source: ActionSource) {
        updateState {
            if (expandedActionTurnId == turnId && expandedActionSource == source) {
                return@updateState this
            }
            copy(expandedActionTurnId = turnId, expandedActionSource = source)
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun resetPacers() {
        textPacer.reset()
        thinkingPacer.reset()
        pacedTextBlockId = null
        pacedThinkingBlockId = null
    }

    override fun onCleared() {
        draftSaveJob?.cancel()
        draftSaveJob = null
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "niki914_nexus_HomeChatState"
        private const val DRAFT_SAVE_DEBOUNCE_MS = 350L
    }
}
