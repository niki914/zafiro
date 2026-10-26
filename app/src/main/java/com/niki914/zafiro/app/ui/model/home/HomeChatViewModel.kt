// githooks:ignore-file file-token-limit
package com.niki914.zafiro.app.ui.model.home

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.app.conversation.ConversationBranchTree
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.app.ui.model.summariesOf
import com.niki914.zafiro.business.files.FileAttachResult
import com.niki914.zafiro.business.files.FilesService
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrDefault
import com.niki914.zafiro.api.util.tryIgnore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

internal data class HomeChatConfigDependencies(
    val loadConfigs: suspend () -> LlmConfigsDocument,
) {
    companion object {
        val Default = HomeChatConfigDependencies(
            loadConfigs = { XRepo.llmConfigs.document() },
        )
    }
}

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
    private val conversations: HomeConversationStore = DefaultHomeConversationStore,
    // 节流器可注入：单测传 delayFn = {} 把放出节奏与状态机解耦（同 TextPacerTest 的用法）
    private val textPacer: TextPacer = TextPacer(),
    // thinking 与正文在流中交织（thinking → tool → text），坐标系独立，单独实例
    private val thinkingPacer: TextPacer = TextPacer(),
    private val configDependencies: HomeChatConfigDependencies = HomeChatConfigDependencies.Default,
) : ComposeMVIViewModel<HomeChatIntent, HomeChatUiState, HomeChatEffect>() {
    private val agentManager: AgentManager get() = requireService()
    private val agent: Agent get() = agentManager.main
    private val files: FilesService get() = requireService()
    private var draftSaveJob: Job? = null
    private var startupRestoreAttempted = false
    private var agentObserveJob: Job? = null
    private var currentBoundAgent: Agent? = null

    /**
     * 前台审批来源：请求落到 [HomeChatUiState.pendingApproval]，
     * UI 裁决后经 [HomeChatIntent.ResolveApproval] 回灌。
     * 在途请求被取消（别处先结算 / 回合被停 / 本 ViewModel 销毁）时收起对话框。
     */
    private val approvalApprover = ForegroundDialogApprover { pending ->
        updateState { copy(pendingApproval = pending) }
    }

    /** 正在节流的文本块；块 id 变化 = 新段开始，节流器归零。 */
    private var pacedTextBlockId: String? = null

    /** 正在节流的思考块。 */
    private var pacedThinkingBlockId: String? = null
    private var conversationBeingLoaded: String? = null

    init {
        observeAgent()
        restoreStartupState()
        viewModelScope.launch {
            refreshSavedConfigs()
        }
    }

    override fun initUiState(): HomeChatUiState = HomeChatUiState()

    override suspend fun handleIntent(intent: HomeChatIntent) {
        when (intent) {
            is HomeChatIntent.InputChanged -> onInputChanged(intent.value)
            HomeChatIntent.Send -> sendCurrentInput()
            HomeChatIntent.Warmup -> agent.warmup()
            is HomeChatIntent.ImageAttached -> attachImage(intent.uri)
            is HomeChatIntent.ImageRemoved -> removeImage(intent.id)
            is HomeChatIntent.FileAttached -> attachFile(intent.uri)
            is HomeChatIntent.FileRemoved -> removeFile(intent.id)
            is HomeChatIntent.CameraCaptured -> attachCameraImage(intent.uri, intent.path)
            HomeChatIntent.StopGenerating -> stopGenerating()
            HomeChatIntent.NewConversation -> startNewConversation()
            is HomeChatIntent.LoadConversation ->
                loadConversation(intent.id, preferLatestBranch = true)
            is HomeChatIntent.ClearUnread -> clearUnread(intent.conversationId)
            is HomeChatIntent.DeleteConversation -> deleteConversationNow(intent.id)
            is HomeChatIntent.ToggleActionKey -> toggleActionKey(intent.key, intent.isExpanded)
            is HomeChatIntent.ToggleActionRow -> toggleActionRow(intent.turnId, intent.source)
            is HomeChatIntent.ReGenerateAt -> reGenerateAt(intent.turnId)
            is HomeChatIntent.SwitchTurnBranch -> switchTurnBranch(intent.turnId, intent.delta)
            is HomeChatIntent.ForkAt -> forkAt(intent.turnId)
            is HomeChatIntent.RewindAt -> rewindAt(intent.turnId)
            is HomeChatIntent.ResolveApproval -> approvalApprover.settle(intent.decision)
            HomeChatIntent.OpenConfigMenu -> {
                refreshSavedConfigs()
                updateState { copy(isConfigMenuVisible = true) }
            }

            HomeChatIntent.DismissConfigMenu -> {
                updateState { copy(isConfigMenuVisible = false) }
            }

            is HomeChatIntent.SelectConfig -> {
                updateState {
                    copy(
                        boundConfigId = intent.id,
                        isConfigMenuVisible = false,
                    )
                }
                currentBoundAgent?.bindConfig(intent.id)
                val currentId = currentState.currentConversationId
                if (!currentId.isNullOrBlank()) {
                    viewModelScope.launch {
                        runCatchingCancellable {
                            conversations.updateConfigId(currentId, intent.id)
                        }.onFailure { throwable ->
                            Logger.w(
                                LOG_TAG,
                                "updateConfigId failed id=$currentId: ${throwable.message}"
                            )
                        }
                    }
                }
            }

            HomeChatIntent.RefreshConfigs -> {
                refreshSavedConfigs()
            }
        }
    }

    private suspend fun refreshSavedConfigs() {
        runCatchingCancellable {
            val document = configDependencies.loadConfigs()
            updateState {
                copy(
                    savedConfigs = summariesOf(document),
                    globalActiveConfigId = document.activeId.orEmpty(),
                )
            }
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "refreshSavedConfigs failed: ${throwable.message}")
        }
    }

    // ── Agent 观察 ──────────────────────────────────────────────────────────

    private fun detachBoundAgent() {
        agentObserveJob?.cancel()
        agentObserveJob = null
        currentBoundAgent?.removeApprover(approvalApprover)
        currentBoundAgent = null
    }

    private fun attachBoundAgent(newAgent: Agent) {
        if (currentBoundAgent === newAgent) return
        detachBoundAgent()
        newAgent.addApprover(approvalApprover)
        currentBoundAgent = newAgent
        bindAgent(newAgent)
    }

    private fun observeAgent() {
        viewModelScope.launch {
            agentManager.mainFlow.collect { newAgent ->
                attachBoundAgent(newAgent)
            }
        }
    }

    private fun bindAgent(targetAgent: Agent) {
        agentObserveJob?.cancel()
        agentObserveJob = viewModelScope.launch {
            launch {
                targetAgent.status.collect { status ->
                    updateState { copy(isGenerating = status.isRunning) }
                }
            }
            launch {
                targetAgent.conversation.collectLatest { conversation ->
                    if (!currentState.isLoadingConversation) {
                        applyConversation(conversation, animateTail = true)
                    }
                }
            }
            launch {
                targetAgent.contextUsage.collect { usage ->
                    updateState { copy(contextProgress = usage?.progress) }
                }
            }
            launch {
                targetAgent.draft.collect { draft ->
                    if (!currentState.isLoadingConversation) {
                        val pendingImages = draft.images.mapNotNull { it.toHomeImage() }
                        val pendingFiles = draft.files.map { it.toHomeFile() }
                        updateState {
                            copy(pendingImages = pendingImages, pendingFiles = pendingFiles)
                        }
                    }
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
        val isFirstConversationAssignment = currentState.currentConversationId == null && id != null
        val family = if (!id.isNullOrBlank()) {
            tryOrDefault(emptyList()) { conversations.branchGroup(id) }
        } else {
            emptyList()
        }
        val turnBranches = if (!id.isNullOrBlank()) {
            ConversationBranchTree.computeTurnBranches(
                currentConversationId = id,
                turns = conversation.turns,
                family = family,
            )
        } else {
            emptyMap()
        }
        updateState {
            copy(
                turns = target,
                currentConversationId = id,
                currentConversationTitle = title,
                turnBranches = turnBranches,
                conversationVersion = conversationVersion + 1,
                userToggledKeys = if (id != currentConversationId) emptyMap() else userToggledKeys,
            )
        }
        Logger.i(LOG_TAG, "applyConversation done id=$id turnsCount=${target.size}")
        if (isFirstConversationAssignment && !currentState.boundConfigId.isNullOrBlank()) {
            val assignedId = id!!
            val boundConfig = currentState.boundConfigId!!
            viewModelScope.launch {
                tryIgnore {
                    conversations.updateConfigId(assignedId, boundConfig)
                }
            }
        }
        refreshAutoExpandedThinking()

        if (last != null && paceBlockIndex >= 0) {
            val block = last.blocks.getOrNull(paceBlockIndex)
            val fullText = when (paceKind) {
                "text" -> (block as? HomeChatBlock.Text)?.text
                else -> (block as? HomeChatBlock.Thinking)?.text
            }
            if (fullText != null) {
                val pacer = if (paceKind == "text") textPacer else thinkingPacer
                pacer.pace(fullText.length) { _, to ->
                    updateTailBlock(last.id, paceBlockIndex, paceKind == "text") { currentBlock ->
                        when (currentBlock) {
                            is HomeChatBlock.Text -> currentBlock.copy(text = fullText.take(to))
                            is HomeChatBlock.Thinking -> currentBlock.copy(text = fullText.take(to))
                            else -> currentBlock
                        }
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
            if (turn.blocks.getOrNull(blockIndex)
                    ?.let { (it is HomeChatBlock.Text) == isText } != true
            ) {
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
        // TODO: Move the auto-scroll decision from Compose's activeThinkingKey check into ViewModel state.
        val activeKeys = currentState.turns.flatMap { turn ->
            turn.blocks.mapIndexedNotNull { index, block ->
                if (block is HomeChatBlock.Thinking && !block.isComplete) "${turn.id}_$index" else null
            }
        }
        updateState {
            copy(
                activeThinkingKey = activeKeys.lastOrNull(),
            )
        }
    }

    // ── 输入 ────────────────────────────────────────────────────────────────

    private fun onInputChanged(value: String) {
        updateState { copy(input = value) }
        agent.updateDraft { it.copy(text = value) }
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(DRAFT_SAVE_DEBOUNCE_MS)
            runCatchingCancellable {
                conversations.setDraftText(value)
            }.onFailure { t ->
                Logger.w(LOG_TAG, "save draft failed: ${t.message}")
            }
        }
    }

    /** 选图：追加一个待落盘项，实现侧完成后归约成 Ready，图片条从 `draft` 读回。 */
    private fun attachImage(uri: String) {
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

    /**
     * 附件：解析路径 → 缺全局文件访问权就跳设置页申请 → 拿到才加卡片。
     *
     * 挂起可能长达 60s（等用户从设置页回来），所以放在本协程里而不是 Compose 回调里。
     * 失败经 effect 回吐一句 toast，不占状态。
     */
    private suspend fun attachFile(uri: String) {
        val startedAt = System.currentTimeMillis()
        val result = runCatchingCancellable {
            files.attach(uri)
        }.getOrElse { throwable ->
            Logger.w(LOG_TAG, "attach file failed uri=$uri reason=${throwable.message}")
            FileAttachResult.Unreadable
        }
        // uri 带着 authority + docId，ms 是用户实际感受到的耗时（权限链最长可达分钟级）
        Logger.i(
            LOG_TAG,
            "attach file result=${result::class.simpleName} ms=${System.currentTimeMillis() - startedAt} uri=$uri",
        )
        when (result) {
            is FileAttachResult.Ok -> addFile(result.file)
            FileAttachResult.Unresolvable ->
                sendEffect(HomeChatEffect.FileAttachFailed(FileAttachReason.Unresolvable))

            FileAttachResult.NoPermission ->
                sendEffect(HomeChatEffect.FileAttachFailed(FileAttachReason.NoPermission))

            FileAttachResult.Unreadable ->
                sendEffect(HomeChatEffect.FileAttachFailed(FileAttachReason.Unreadable))
        }
    }

    /** 按 path 去重（同图片按 uri/path 去重）。 */
    private fun addFile(file: FileRef) {
        agent.updateDraft { draft ->
            if (draft.files.any { it.path == file.path }) draft
            else draft.copy(files = draft.files + file)
        }
    }

    private fun removeFile(id: String) {
        agent.updateDraft { draft ->
            draft.copy(files = draft.files.filterNot { it.path.hashCode().toString() == id })
        }
    }

    /**
     * 相机拍完：先走既有图片链路，等 ingest 把字节拷进沙箱之后再删 cache 里那份原图。
     *
     * 不能像入口层那样当场删：那时字节还没被拷走，删了就没源可读了。
     * ingest 失败也删：它只是给相机写字节的落点，留着就是重复占盘，而 cache 目录
     * 不归任何清理逻辑管。
     */
    private suspend fun attachCameraImage(uri: String, path: String) {
        val before = agent.draft.value.images.count { it is DraftImage.Ready }
        attachImage(uri)
        val settled = agent.draft.first { draft ->
            draft.images.none { it is DraftImage.Pending && it.uri == uri }
        }
        if (settled.images.count { it is DraftImage.Ready } == before) {
            Logger.w(LOG_TAG, "camera ingest failed uri=$uri")
        }
        runCatchingCancellable { File(path).delete() }
            .onFailure { Logger.w(LOG_TAG, "camera temp file delete failed path=$path") }
    }

    private suspend fun sendCurrentInput() {
        val trimmedInput = currentState.input.trim()
        val hasAttachments =
            currentState.pendingImages.isNotEmpty() || currentState.pendingFiles.isNotEmpty()
        if (trimmedInput.isEmpty() && !hasAttachments) {
            sendEffect(HomeChatEffect.EmptyInputAttempted)
            return
        }
        agent.updateDraft { it.copy(text = trimmedInput) }
        // stream() is synchronous; don't let it consume Pending images before AgentImpl finishes ingest.
        agent.draft.first { draft -> draft.images.none { it is DraftImage.Pending } }
        updateState { copy(isGenerating = true, unreadTurnId = null) }
        val result = agent.stream()
        Logger.i(LOG_TAG, "send result=$result")
        if (result != TurnStart.Started) {
            updateState {
                copy(isGenerating = agent.status.value.isRunning)
            }
            return
        }
        draftSaveJob?.cancel()
        draftSaveJob = null
        viewModelScope.launch {
            runCatchingCancellable {
                conversations.setDraftText("")
            }.onFailure { t ->
                Logger.w(LOG_TAG, "clear draft failed: ${t.message}")
            }
        }
        // 输入框以本类为准：发起即清空，不从 draft 读回
        updateState {
            copy(
                input = "",
                pendingImages = emptyList(),
                pendingFiles = emptyList(),
                activeThinkingKey = null,
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

    private suspend fun startNewConversation() {
        Logger.d(LOG_TAG, "start new conversation")
        val leavingId = agent.conversation.value.id?.value
        detachBoundAgent()
        draftSaveJob?.cancel()
        draftSaveJob = null
        resetPacers()
        conversationBeingLoaded = null
        val currentInput = currentState.input
        updateState {
            HomeChatUiState(
                input = currentInput,
                savedConfigs = currentState.savedConfigs,
                globalActiveConfigId = currentState.globalActiveConfigId,
                boundConfigId = null,
                isLoadingConversation = true,
            )
        }
        val startedAtMs = System.currentTimeMillis()
        try {
            refreshSavedConfigs()
            runCatchingCancellable {
                if (!leavingId.isNullOrBlank()) {
                    runCatchingCancellable { conversations.bindConfig(leavingId) }
                        .onFailure { throwable ->
                            Logger.w(LOG_TAG, "bindConfig failed leavingId=$leavingId", throwable)
                        }
                }
                val newAgent = agentManager.startNewConversation()
                attachBoundAgent(newAgent)
                if (currentInput.isNotEmpty()) {
                    newAgent.updateDraft { it.copy(text = currentInput) }
                }
                conversations.setLastOpenedConversationId("")
                Logger.i(
                    LOG_TAG,
                    "new conversation done elapsedMs=${System.currentTimeMillis() - startedAtMs}",
                )
            }.onFailure { throwable ->
                Logger.e(LOG_TAG, "new conversation failed", throwable)
            }
        } finally {
            updateState { copy(isLoadingConversation = false) }
        }
    }

    private fun restoreStartupState() {
        if (startupRestoreAttempted) return
        startupRestoreAttempted = true
        viewModelScope.launch {
            val draft = tryOrDefault("") { conversations.draftText() }
            if (draft.isNotEmpty()) {
                updateState { copy(input = draft) }
                agent.updateDraft { it.copy(text = draft) }
            }
            val shouldRestore = tryOrDefault(true) { conversations.loadLastConversationOnStartup() }
            if (!shouldRestore) {
                Logger.d(LOG_TAG, "restore skipped loadLastConversationOff")
                return@launch
            }
            val conversationId = conversations.lastOpenedConversationId()
            if (conversationId.isBlank()) {
                Logger.d(LOG_TAG, "restore skipped lastOpenedConversationBlank")
                return@launch
            }
            runCatchingCancellable {
                loadConversation(conversationId, preferLatestBranch = true)
            }.onFailure { t ->
                Logger.e(
                    LOG_TAG,
                    "restoreStartupState failed for id=$conversationId, falling back to clean state: ${t.message}",
                    t,
                )
                tryIgnore { conversations.setLastOpenedConversationId("") }
                updateState { HomeChatUiState(input = currentState.input).withClearedTransient() }
            }
        }
    }

    /**
     * 载入会话。
     *
     * [preferLatestBranch] 为 true 时（列表点击、冷启动恢复）先落回分支组最新的一版：
     * 「打开这个会话」的语义是看它最新长什么样。
     * 翻页与重新生成后的载入自带明确目标，传 false。
     */
    private suspend fun loadConversation(id: String, preferLatestBranch: Boolean = false) {
        val target = if (preferLatestBranch) {
            tryOrDefault(id) {
                val record = conversations.getConversation(id)
                val rootId = record?.summary?.rootId
                if (rootId.isNullOrBlank()) id else conversations.latestBranchId(rootId) ?: id
            }
        } else {
            id
        }
        if (target != id) {
            Logger.i(LOG_TAG, "load conversation id=$id resolved to latest branch=$target")
        }
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "load conversation id=$target started")
        val leavingId = agent.conversation.value.id?.value
        if (leavingId != target) {
            detachBoundAgent()
            if (!leavingId.isNullOrBlank()) {
                runCatchingCancellable { conversations.bindConfig(leavingId) }
                    .onFailure { throwable ->
                        Logger.w(LOG_TAG, "bindConfig failed leavingId=$leavingId", throwable)
                    }
            }
        }
        draftSaveJob?.cancel()
        draftSaveJob = null
        resetPacers()
        conversationBeingLoaded = target
        updateState { copy(isLoadingConversation = true) }
        try {
            val record = conversations.getConversation(target)
            if (record == null) {
                conversationBeingLoaded = null
                Logger.w(
                    LOG_TAG,
                    "load conversation id=$target notFound " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}",
                )
                return
            }
            val title = record.summary.title.takeIf { it.isNotBlank() }
            conversationBeingLoaded = target
            val targetAgent = agentManager.switchTo(target)
            attachBoundAgent(targetAgent)
            val hadUnread = record.summary.hasUnread
            val unreadTurnId = if (hadUnread) {
                targetAgent.conversation.value.turns.indices.lastOrNull()?.toLong()
            } else {
                null
            }
            updateState {
                copy(
                    currentConversationId = target,
                    currentConversationTitle = title,
                    boundConfigId = record.configId,
                    unreadTurnId = unreadTurnId,
                ).withClearedTransient()
            }
            applyConversation(targetAgent.conversation.value, animateTail = false)
            conversations.setLastOpenedConversationId(target)
            conversations.clearUnread(target)
            Logger.i(
                LOG_TAG,
                "load conversation done id=$target elapsedMs=${System.currentTimeMillis() - startedAtMs}",
            )
        } catch (throwable: Throwable) {
            conversationBeingLoaded = null
            throw throwable
        } finally {
            updateState { copy(isLoadingConversation = false) }
            if (agent.conversation.value.id?.value != target) conversationBeingLoaded = null
        }
    }

    private suspend fun clearUnread(id: String) {
        conversations.clearUnread(id)
        updateState { copy(unreadTurnId = null) }
    }

    // ── 历史派生操作 ────────────────────────────────────────────────────────
    // TODO(收进 Agent)：契约暂无 fork / delete 命令，组合仍留在业务侧：
    //  仓储按回合截断派生（forkAtTurn）→ load（Agent）→ stream（Agent）。
    //  截断点与回填内容由仓储从持久化条目算，本层只见回合下标与契约类型。

    private suspend fun reGenerateAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val result = conversations.forkAtTurn(currentId, turnId.toInt(), ForkKind.Regenerate)
            ?: return
        Logger.i(
            LOG_TAG,
            "regenerate fork sourceId=$currentId turnId=$turnId newId=${result.newConversationId}"
        )
        val preservedKeys = currentState.userToggledKeys.filterKeys { k ->
            val id = k.substringBefore('_').toLongOrNull() ?: 0L
            id < turnId
        }
        loadConversation(result.newConversationId)
        updateState { copy(userToggledKeys = preservedKeys) }
        agent.updateDraft { draft ->
            draft.copy(
                text = result.promptText,
                images = result.images.map { DraftImage.Ready(it) },
                files = result.files,
            )
        }
        if (agent.stream() == TurnStart.Started) {
            updateState { copy(input = "") }
        }
    }

    private suspend fun switchTurnBranch(turnId: Long, delta: Int) {
        if (currentState.isGenerating) return
        val branchState = currentState.turnBranches[turnId] ?: return
        if (branchState.total <= 1) return
        val currentIndex = branchState.current - 1
        val targetIndex = currentIndex + delta
        if (targetIndex !in branchState.variantConversationIds.indices) return
        val targetId = branchState.variantConversationIds[targetIndex]
        if (targetId == currentState.currentConversationId) return
        Logger.i(
            LOG_TAG,
            "switch turn branch turnId=$turnId from=${currentIndex + 1} to=${targetIndex + 1} " +
                    "total=${branchState.total} target=$targetId"
        )
        loadConversation(targetId)
    }

    private suspend fun forkAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val result = conversations.forkAtTurn(currentId, turnId.toInt(), ForkKind.Fork)
            ?: return
        Logger.i(
            LOG_TAG,
            "fork sourceId=$currentId turnId=$turnId newId=${result.newConversationId}"
        )
        val preservedKeys = currentState.userToggledKeys.filterKeys { k ->
            val id = k.substringBefore('_').toLongOrNull() ?: 0L
            id < turnId
        }
        loadConversation(result.newConversationId)
        updateState { copy(userToggledKeys = preservedKeys) }
    }

    private suspend fun rewindAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = agent.conversation.value.id?.value ?: return
        val result = conversations.forkAtTurn(currentId, turnId.toInt(), ForkKind.Rewind)
            ?: return
        Logger.i(
            LOG_TAG,
            "rewind sourceId=$currentId turnId=$turnId newId=${result.newConversationId}"
        )
        val preservedKeys = currentState.userToggledKeys.filterKeys { k ->
            val id = k.substringBefore('_').toLongOrNull() ?: 0L
            id < turnId
        }
        loadConversation(result.newConversationId)
        agent.updateDraft { draft ->
            draft.copy(
                text = result.promptText,
                images = result.images.map { DraftImage.Ready(it) },
                files = result.files,
            )
        }
        updateState {
            copy(
                input = result.promptText,
                pendingImages = result.images.map { it.toHomeImage() },
                pendingFiles = result.files.map { it.toHomeFile() },
                expandedActionTurnId = null,
                expandedActionSource = null,
                userToggledKeys = preservedKeys,
            )
        }
    }

    internal suspend fun deleteConversationNow(id: String) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "delete conversation id=$id started")
        val groupIds = conversations.branchGroupIds(id)
        val currentOpenId = agent.conversation.value.id?.value
        val deletingCurrent = id == currentOpenId || groupIds.contains(currentOpenId)
        if (deletingCurrent) {
            startNewConversation()
        }
        groupIds.forEach { branchId ->
            tryIgnore { agentManager.destroy(branchId) }
        }
        conversations.deleteConversation(id)
        if (!deletingCurrent) {
            Logger.d(LOG_TAG, "delete conversation id=$id notCurrent skipped")
            return
        }
        Logger.i(
            LOG_TAG,
            "delete conversation done id=$id elapsedMs=${System.currentTimeMillis() - startedAtMs}",
        )
    }

    // ── 展开态 ──────────────────────────────────────────────────────────────

    private fun toggleActionKey(key: String, isExpanded: Boolean) {
        updateState {
            copy(userToggledKeys = userToggledKeys + (key to isExpanded))
        }
    }

    private fun toggleActionRow(turnId: Long, source: ActionSource) {
        updateState {
            val currentTurnId = expandedActionTurnId
            val currentSource = expandedActionSource
            if (currentTurnId == turnId && currentSource == source) {
                copy(expandedActionTurnId = null, expandedActionSource = null)
            } else {
                copy(expandedActionTurnId = turnId, expandedActionSource = source)
            }
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
        // 在途请求交给其他来源（或按拒绝结算）；本类不再持有来源身份
        approvalApprover.detach()
        detachBoundAgent()
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_HomeChatState"
        private const val DRAFT_SAVE_DEBOUNCE_MS = 350L
    }
}

/**
 * 前台对话框的审批来源：单槽挂起，把在途请求交给构造时传入的回调渲染，
 * 等 [settle]（用户裁决）或 [detach]（ViewModel 销毁）回灌。
 * 同一时刻只支持一个在途请求。
 */
private class ForegroundDialogApprover(
    private val onPendingChanged: (ApprovalRequest?) -> Unit,
) : Approver {
    private var cont: CancellableContinuation<ApprovalDecision>? = null

    override suspend fun decide(request: ApprovalRequest): ApprovalDecision =
        suspendCancellableCoroutine { current ->
            cont = current
            onPendingChanged(request)
            current.invokeOnCancellation {
                if (cont === current) cont = null
                onPendingChanged(null)
            }
        }

    /** UI 裁决入口。 */
    fun settle(decision: ApprovalDecision) {
        val current = cont
        cont = null
        onPendingChanged(null)
        current?.resume(decision)
    }

    /** 注销前上报在途请求：取消挂起，让 agent 按「来源没了」继续结算。 */
    fun detach() {
        val current = cont
        cont = null
        onPendingChanged(null)
        current?.cancel()
    }
}
