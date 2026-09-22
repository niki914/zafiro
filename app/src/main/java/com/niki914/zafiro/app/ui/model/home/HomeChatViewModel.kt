package com.niki914.zafiro.app.ui.model.home

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.model.home.tmp.DefaultHomeConversationStore
import com.niki914.zafiro.app.ui.model.home.tmp.LlmHomeChatRuntime
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.collections.plus


class HomeChatViewModel internal constructor(
    private val runtime: HomeChatRuntime = LlmHomeChatRuntime,
    private val conversations: HomeConversationStore = DefaultHomeConversationStore,
    // 节流器可注入：单测传 delayFn = {} 把放出节奏与状态机解耦（同 TextPacerTest 的用法）
    private val textPacer: TextPacer = TextPacer(),
    // thinking 与正文在流中交织（thinking → tool → text），坐标系独立，单独实例
    private val thinkingPacer: TextPacer = TextPacer(),
) : ComposeMVIViewModel<HomeChatIntent, HomeChatUiState, Nothing>() {
    private var nextTurnId = 0L
    private var streamJob: Job? = null
    private var draftSaveJob: Job? = null
    private var currentConversationId: String? = null
    private var startupRestoreAttempted = false

    init {
        restoreLastConversationOnStartup()
    }

    override fun initUiState(): HomeChatUiState {
        return HomeChatUiState()
    }

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
            is HomeChatIntent.ToggleToolRun -> toggleToolRun(
                intent.turnId, intent.runStartIndex,
            )

            is HomeChatIntent.ToggleToolResult -> toggleToolResult(
                intent.turnId, intent.runStartIndex, intent.toolIndex,
            )

            is HomeChatIntent.ToggleThinking -> toggleThinking(intent.turnId, intent.blockIndex)

            is HomeChatIntent.ToggleActionRow -> toggleActionRow(
                intent.turnId, intent.source,
            )

            is HomeChatIntent.ReGenerateAt -> reGenerateAt(intent.turnId)
            is HomeChatIntent.ForkAt -> forkAt(intent.turnId)
            is HomeChatIntent.RewindAt -> rewindAt(intent.turnId)
        }
    }

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

    private fun onInputChanged(value: String) {
        updateState { copy(input = value) }
        val conversationId = currentConversationId ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            conversations.updateDraft(conversationId = conversationId, draftText = value)
        }
    }

    /**
     * 相册选图 → ingest → pendingImages。仅 UI 预览链路：
     * 图片不进 runtime.stream（发送链路待 Okia.send 支持图片参数后接入）。
     */
    private suspend fun attachImage(uri: String) {
        if (currentState.isGenerating) return
        val image = try {
            runtime.ingestImage(uri)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "image ingest failed uri=$uri error=${throwable.message}")
            null
        }
        if (image != null) {
            updateState { copy(pendingImages = pendingImages + image) }
        }
    }

    private fun removeImage(id: String) {
        updateState { copy(pendingImages = pendingImages.filterNot { it.id == id }) }
    }

    private suspend fun sendCurrentInput() {
        val query = currentState.input.trim()
        val pendingImages = currentState.pendingImages
        if (query.isBlank() && pendingImages.isEmpty() || currentState.isGenerating) {
            Logger.d(
                LOG_TAG,
                "send skipped blank=${query.isBlank()} images=${pendingImages.size} " +
                        "isGenerating=${currentState.isGenerating}"
            )
            return
        }
        Logger.i(LOG_TAG, "send requested queryLength=${query.length} images=${pendingImages.size}")

        val turnId = nextTurnId++
        val imageBlocks = pendingImages.map { ContentBlock.Image(it.path, "image/jpeg") }
        updateState {
            copy(
                input = "",
                // 待发图片移入 turn（UI 展示链路）
                pendingImages = emptyList(),
                // 新回合开始：清除旧错误卡片（瞬态 UI 态，T3 TODO②——
                // 错误只在当轮显示，下一轮发起即消失）
                turns = turns.map { turn ->
                    turn.copy(
                        blocks = turn.blocks.filterNot {
                            it is HomeChatBlock.Error || it is HomeChatBlock.Retrying
                        },
                    )
                } + HomeChatTurn(id = turnId, userText = query, images = pendingImages),
                isGenerating = true,
                lastEventName = null,
                streamEventCount = 0,
                activeThinkingKey = null,
                autoExpandedThinking = emptySet(),
                // 旧回合的操作行（复制/重试）随新回合消失：旧 turn 失去 isLastTurn 后
                // canToggleUserAction 变 false，不清则操作行永远挂着无法收回
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
        draftSaveJob?.cancel()
        draftSaveJob = null

        streamJob = viewModelScope.launch {
            try {
                val conversationId = ensureCurrentConversation(query)
                conversations.updateDraft(conversationId = conversationId, draftText = "")
                Logger.i(
                    LOG_TAG,
                    "send turn started turnId=$turnId conversationId=$conversationId queryLength=${query.length}"
                )
                collectLlmStream(turnId = turnId, query = query, images = imageBlocks)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Logger.e(
                    LOG_TAG,
                    "send turn failed turnId=$turnId errorType=${throwable::class.simpleName} " +
                            "message=${throwable.message}"
                )
                throwable.message?.let { message ->
                    applyError(turnId = turnId, message = message, code = null)
                }
            } finally {
                if (streamJob == currentCoroutineContext()[Job]) {
                    streamJob = null
                    updateState { copy(isGenerating = false) }
                }
            }
        }
    }

    private suspend fun stopGenerating() {
        if (!currentState.isGenerating) return
        runtime.stopCurrentRound()
        streamJob?.cancel()
        streamJob = null
        finalizeRunningTools()
        updateState { copy(isGenerating = false, activeThinkingKey = null) }
    }

    private fun startNewConversation() {
        Logger.d(LOG_TAG, "start new conversation")
        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        currentConversationId = null
        nextTurnId = 0L
        updateState { HomeChatUiState() }
        viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            try {
                // D3-9：先 stop（终止回合）再丢弃实例（kill 工具资源 + close），
                // 避免 close 撞活跃回合（OKIA §8.7 #5）
                runtime.stopCurrentRound()
                runtime.resetConversation()
                conversations.setLastOpenedConversationId("")
                Logger.i(
                    LOG_TAG,
                    "new conversation done " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    private suspend fun collectLlmStream(turnId: Long, query: String, images: List<ContentBlock.Image> = emptyList()) {
        textPacer.reset()
        // Mapper 的 thinking id 跨轮复用（id 0 每轮重新出现），回合开始必须归零
        thinkingPacer.reset()
        runtime.stream(query, images).collect { event ->
            val eventName = eventName(event)
            val eventCount = currentState.streamEventCount + 1
            updateState {
                copy(
                    lastEventName = eventName,
                    streamEventCount = eventCount,
                )
            }
            when {
                event is LlmStreamEvent.TextDelta -> paceTextDelta(turnId, event)
                event is LlmStreamEvent.ThinkingStarted || event is LlmStreamEvent.ThinkingEnded ->
                    paceThinking(turnId, event)

                else -> applyEvent(turnId = turnId, event = event)
            }
        }
    }

    /**
     * 按 [TextPacer] 节奏放出当前文本段的增量。fullText 是累积全量（跨块只在
     * TextEnded 重置），released 保持同一坐标系，切出 [from, to) 的增量。
     * 流被取消（停止/新会话）时在 finally 里立即追平，避免丢尾部文本。
     */
    private suspend fun paceTextDelta(turnId: Long, event: LlmStreamEvent.TextDelta) {
        val fullText = event.fullText
        if (event.isSegmentStart) {
            // 段边界（工具块等之后的新文本段）：fullText 从新坐标开始，节流器同步归零。
            // 不重置则新段长度 ≤ 已放出字符数时 pace 直接 return，整段被静默丢弃。
            Logger.d(
                LOG_TAG,
                "pace segment reset turnId=$turnId fullTextLen=${fullText.length} released=${textPacer.released}"
            )
            textPacer.reset()
        }
        try {
            textPacer.pace(fullText.length) { from, to ->
                applyEvent(
                    turnId = turnId,
                    event = event.copy(
                        delta = fullText.substring(from, to),
                        fullText = fullText.take(to),
                    ),
                )
            }
        } catch (e: CancellationException) {
            val from = textPacer.released
            if (from < fullText.length) {
                textPacer.syncReleased(fullText.length)
                applyEvent(
                    turnId = turnId,
                    event = event.copy(
                        delta = fullText.substring(from),
                        fullText = fullText,
                    ),
                )
            }
            throw e
        }
    }

    /**
     * Thinking 事件复用正文节流：ThinkingStarted/Ended 的 text 都是块内累积全量，
     * 与 TextDelta.fullText 同坐标，首发时 reset、后续 pace。B 方案：
     * ThinkingEnded 也走 pace（尾部最多 0.2s 放完），不瞬间追平；取消（停止/新会话）
     * 时立即追平，保证已收到的思考内容全部展示。
     */
    private suspend fun paceThinking(turnId: Long, event: LlmStreamEvent) {
        val (id, fullText, isEnded) = when (event) {
            is LlmStreamEvent.ThinkingStarted -> Triple(event.id, event.text, false)
            is LlmStreamEvent.ThinkingEnded -> Triple(event.id, event.text, true)
            else -> return
        }
        // 新块首发（同 id 块不存在于当前回合）：坐标归零。Mapper 对续接重发同 id。
        val isNewBlock = currentState.turns.any { turn ->
            turn.blocks.any { it is HomeChatBlock.Thinking && it.id == id }
        }.not()
        if (isNewBlock) {
            thinkingPacer.reset()
        }
        try {
            thinkingPacer.pace(fullText.length) { from, to ->
                applyEvent(
                    turnId = turnId,
                    event = LlmStreamEvent.ThinkingStarted(
                        id = id,
                        text = fullText.substring(0, to),
                    ),
                )
            }
        } catch (e: CancellationException) {
            val from = thinkingPacer.released
            if (from < fullText.length) {
                thinkingPacer.syncReleased(fullText.length)
                applyEvent(
                    turnId = turnId,
                    event = LlmStreamEvent.ThinkingStarted(id = id, text = fullText),
                )
            }
            // 停止路径可能先于取消传播清掉 activeThinkingKey，追平的 applyEvent
            // 若是首发会重新置位；取消后不该再有 active 思考块
            updateState { copy(activeThinkingKey = null) }
            throw e
        }
        if (isEnded) {
            applyEvent(turnId = turnId, event = event)
        }
    }

    private suspend fun applyEvent(turnId: Long, event: LlmStreamEvent) {
        when (event) {
            LlmStreamEvent.RoundStarted -> updateState { copy(isGenerating = true) }
            is LlmStreamEvent.TextDelta -> {
                clearRetrying(turnId)
                updateTurn(turnId) {
                    it.appendText(event.delta)
                }
            }

            is LlmStreamEvent.ThinkingStarted -> {
                clearRetrying(turnId)
                updateState {
                    val turnIndex = turns.indexOfFirst { it.id == turnId }
                    if (turnIndex == -1) return@updateState this
                    // 回声 = 同 id 块已存在（Mapper 对 Delta 续接重发同 id）；首发 = 块不存在
                    val isEcho = turns[turnIndex].blocks.any {
                        it is HomeChatBlock.Thinking && it.id == event.id
                    }
                    val updated = turns[turnIndex].upsertThinking(event.id, event.text)
                    val thinkingIndex = updated.blocks.indexOfLast {
                        it is HomeChatBlock.Thinking && it.id == event.id
                    }
                    val key = if (thinkingIndex != -1) "${turnId}_$thinkingIndex" else null
                    if (key == null) {
                        return@updateState copy(
                            turns = turns.toMutableList().also { it[turnIndex] = updated },
                        )
                    }
                    if (isEcho) {
                        // 续接回声：只更新文本，不碰展开态（手动收起后不被续接重新撑开）
                        return@updateState copy(
                            turns = turns.toMutableList().also { it[turnIndex] = updated },
                        )
                    }
                    // 新块首发：自动展开，并收起所有仍自动展开的旧块（autoExpandedThinking
                    // 只含未被用户干预的块，用户手动展开的保留）
                    copy(
                        turns = turns.toMutableList().also { it[turnIndex] = updated },
                        expandedThinking = (expandedThinking - autoExpandedThinking) + key,
                        autoExpandedThinking = autoExpandedThinking + key,
                        activeThinkingKey = key,
                    )
                }
            }

            is LlmStreamEvent.ThinkingEnded -> {
                clearRetrying(turnId)
                updateTurn(turnId) { it.upsertThinking(event.id, event.text) }
                // 思考结束：只摘 active 指针（停止滚动跟随），块保持展开不收起
                updateState { copy(activeThinkingKey = null) }
            }

            is LlmStreamEvent.ToolPending -> {
                clearRetrying(turnId)
                updateTurn(turnId) {
                    // 占位行：仅名字已知，Running 态转圈、不可展开；后续 ToolRunning 按 callId 原地更新
                    it.updateTool(event.call, HomeToolState.Running)
                }
            }

            is LlmStreamEvent.ToolRunning -> {
                clearRetrying(turnId)
                updateTurn(turnId) {
                    // updateTool 而非 appendTool：参数构建期可能已插入占位行，按 callId 原地更新
                    it.updateTool(event.call, HomeToolState.Running)
                }
            }

            is LlmStreamEvent.ToolSucceeded -> updateTurn(turnId) {
                it.updateTool(
                    event.call,
                    HomeToolState.Succeeded, event.outputText,
                    images = event.images,
                )
            }

            is LlmStreamEvent.ToolFailed -> updateTurn(turnId) {
                it.updateTool(
                    event.call,
                    HomeToolState.Failed, event.resultText, event.message,
                )
            }

            is LlmStreamEvent.Error -> {
                Logger.e(
                    LOG_TAG,
                    "apply error turnId=$turnId code=${event.code} message=${event.message}"
                )
                clearRetrying(turnId)
                applyError(
                    turnId = turnId,
                    message = event.message,
                    code = event.code,
                    attempts = event.attempts,
                )
            }

            is LlmStreamEvent.Retrying -> {
                Logger.w(
                    LOG_TAG,
                    "apply retrying turnId=$turnId attempt=${event.attempt}/${event.maxAttempts} " +
                            "delayMs=${event.delayMs} reason=${event.reason}"
                )
                updateTurn(turnId) { turn ->
                    // 同回合只有一张 retry 卡片：替换旧的，不叠加
                    val withoutStale = turn.copy(
                        blocks = turn.blocks.filterNot { it is HomeChatBlock.Retrying },
                    )
                    withoutStale.copy(
                        blocks = withoutStale.blocks + HomeChatBlock.Retrying(
                            attempt = event.attempt,
                            maxAttempts = event.maxAttempts,
                            delayMs = event.delayMs,
                            reason = event.reason,
                        ),
                    )
                }
            }

            is LlmStreamEvent.Completed -> {
                Logger.i(
                    LOG_TAG,
                    "apply completed turnId=$turnId",
                )
                updateState { copy(isGenerating = false, activeThinkingKey = null) }
            }
        }
    }

    private fun restoreLastConversationOnStartup() {
        if (startupRestoreAttempted) return
        startupRestoreAttempted = true
        viewModelScope.launch {
            // General Settings 开关：默认关（冷启动进入新对话），打开才恢复上次会话
            val shouldRestore = runCatching { conversations.loadLastConversationOnStartup() }
                .getOrDefault(false)
            if (!shouldRestore) {
                Logger.d(LOG_TAG, "restore skipped loadLastConversationOff")
                return@launch
            }
            restoreLastConversation()
        }
    }

    private suspend fun restoreLastConversation() {
        val startedAtMs = System.currentTimeMillis()
        updateState { copy(isLoadingConversation = true) }
        viewModelScope.launch {
            try {
                val conversationId = conversations.lastOpenedConversationId()
                if (conversationId.isBlank()) {
                    Logger.d(LOG_TAG, "restore skipped lastOpenedConversationBlank")
                    return@launch
                }
                val record = conversations.getConversation(conversationId)
                if (record == null) {
                    Logger.w(LOG_TAG, "restore notFound id=$conversationId")
                    return@launch
                }
                runtime.openSession(record.snapshot)
                currentConversationId = conversationId
                val restoredTurns = ConversationFormatter.toHomeTurns(record.snapshot)
                val restoredTitle = record.summary.title.takeIf {
                    restoredTurns.isNotEmpty() && it.isNotBlank()
                }
                nextTurnId = restoredTurns.nextTurnId()
                updateState {
                    copy(
                        input = record.draftText,
                        turns = restoredTurns,
                        isLoadingConversation = false,
                        isGenerating = false,
                        lastEventName = null,
                        streamEventCount = 0,
                        currentConversationId = conversationId,
                        currentConversationTitle = restoredTitle,
                    ).withClearedTransient()
                }
                Logger.i(
                    LOG_TAG,
                    "restore done id=$conversationId turnCount=${restoredTurns.size} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
            } finally {
                updateState { copy(isLoadingConversation = false) }
            }
        }
    }

    private fun finalizeRunningTools() {
        val currentTurns = currentState.turns
        if (currentTurns.isEmpty()) return
        val lastTurn = currentTurns.last()
        val hasRunning = lastTurn.blocks.any { block ->
            block is HomeChatBlock.Tool && block.status.state == HomeToolState.Running
        }
        if (!hasRunning) return
        updateTurn(lastTurn.id) { turn ->
            turn.copy(
                blocks = turn.blocks.map { block ->
                    if (block is HomeChatBlock.Tool && block.status.state == HomeToolState.Running) {
                        HomeChatBlock.Tool(
                            block.status.copy(
                                state = HomeToolState.Failed,
                                failedReason = FAILED_REASON_INTERRUPTED,
                            ),
                        )
                    } else {
                        block
                    }
                },
            )
        }
    }

    private suspend fun loadConversation(id: String) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "load conversation id=$id started")
        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        // D3-9：先 stop（终止回合 + kill 工具资源）再关实例换树，
        // 避免 close 撞活跃回合（OKIA §8.7 #5）
        runtime.stopCurrentRound()
        updateState { copy(isLoadingConversation = true) }
        try {
            val record = conversations.getConversation(id)
            if (record == null) {
                Logger.w(
                    LOG_TAG,
                    "load conversation id=$id notFound " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
                return
            }
            runtime.openSession(record.snapshot)
            currentConversationId = id
            conversations.setLastOpenedConversationId(id)
            val restoredTurns = ConversationFormatter.toHomeTurns(record.snapshot)
            val restoredTitle = record.summary.title.takeIf {
                restoredTurns.isNotEmpty() && it.isNotBlank()
            }
            nextTurnId = restoredTurns.nextTurnId()
            updateState {
                copy(
                    input = "",
                    turns = restoredTurns,
                    isLoadingConversation = false,
                    isGenerating = false,
                    lastEventName = null,
                    streamEventCount = 0,
                    currentConversationId = id,
                    currentConversationTitle = restoredTitle,
                ).withClearedTransient()
            }
            Logger.i(
                LOG_TAG,
                "load conversation done id=$id turnCount=${restoredTurns.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        } finally {
            updateState { copy(isLoadingConversation = false) }
        }
    }

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
        val currentId = currentConversationId ?: return
        streamJob?.cancel()
        streamJob = null
        val history = runtime.historySnapshot()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val userTurn = history[userIndex] as? Message.User ?: return
        val userText = userTurn.text()
        // 原回合的图片随 fork 截断丢失，重发时必须带上（否则模型看不到图、
        // UI 新 turn 图片卡消失）
        val userImages = userTurn.content.filterIsInstance<ContentBlock.Image>()
        // D3-10/D3-11：regen = fork（复制截断子树，新会话互不影响）+ 自动 resend
        val newConvId = conversations.forkConversation(currentId, userIndex, ForkKind.Regenerate)
        Logger.i(
            LOG_TAG,
            "regenerate fork sourceId=$currentId turnId=$turnId newId=$newConvId"
        )
        loadConversation(newConvId)
        val newTurnId = nextTurnId++
        updateState {
            copy(
                turns = turns + HomeChatTurn(
                    id = newTurnId,
                    userText = userText,
                    images = userImages.map {
                        HomeChatImage.of(it)
                    },
                ),
                isGenerating = true,
                lastEventName = null,
                streamEventCount = 0,
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
        streamJob = viewModelScope.launch {
            try {
                collectLlmStream(turnId = newTurnId, query = userText, images = userImages)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                throwable.message?.let { message ->
                    applyError(turnId = newTurnId, message = message, code = null)
                }
            } finally {
                if (streamJob == currentCoroutineContext()[Job]) {
                    streamJob = null
                    updateState { copy(isGenerating = false) }
                }
            }
        }
    }

    private suspend fun forkAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = currentConversationId ?: return
        val history = runtime.historySnapshot()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val nextUserIndex = findNextUserIndex(history, userIndex + 1)
        val endIndex = if (nextUserIndex != null) nextUserIndex - 1 else history.lastIndex
        val newConvId = conversations.forkConversation(currentId, endIndex + 1, ForkKind.Fork)
        Logger.i(
            LOG_TAG,
            "fork sourceId=$currentId turnId=$turnId endIndex=$endIndex newId=$newConvId"
        )
        loadConversation(newConvId)
    }

    private suspend fun rewindAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = currentConversationId ?: return
        streamJob?.cancel()
        streamJob = null
        val history = runtime.historySnapshot()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val userTurn = history[userIndex] as? Message.User ?: return
        val userText = userTurn.text()
        val userImages = userTurn.content.filterIsInstance<ContentBlock.Image>()
        val newConvId = conversations.forkConversation(currentId, userIndex, ForkKind.Rewind)
        Logger.i(
            LOG_TAG,
            "rewind sourceId=$currentId turnId=$turnId newId=$newConvId"
        )
        loadConversation(newConvId)
        updateState {
            copy(
                input = userText,
                pendingImages = userImages.map {
                    HomeChatImage(id = it.path.hashCode().toString(), path = it.path)
                },
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
    }

    internal suspend fun deleteConversationNow(id: String) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "delete conversation id=$id started")
        conversations.deleteConversation(id)
        if (id != currentConversationId) {
            Logger.d(LOG_TAG, "delete conversation id=$id notCurrent skipped")
            return
        }

        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        currentConversationId = null
        nextTurnId = 0L
        // D3-9：先 stop 再丢弃实例（close 撞活跃回合防护）
        runtime.stopCurrentRound()
        runtime.resetConversation()
        conversations.setLastOpenedConversationId("")
        updateState { HomeChatUiState() }
        Logger.i(
            LOG_TAG,
            "delete conversation done id=$id " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    private suspend fun ensureCurrentConversation(firstUserInput: String): String {
        currentConversationId?.let { return it }
        // T3：新会话实例由 LLMController 惰性创建（ensureSession），
        // 树 id 即 Room 会话 id（对齐，open(restore) 恢复时从快照 id 取）
        val sessionId = runtime.ensureSession()
        conversations.createConversation(id = sessionId, firstUserInput = firstUserInput)
        currentConversationId = sessionId
        conversations.setLastOpenedConversationId(sessionId)
        Logger.i(LOG_TAG, "conversation created id=$sessionId")
        updateState {
            copy(
                currentConversationId = sessionId,
                currentConversationTitle = ConversationFormatter
                    .titleFromFirstInput(firstUserInput)
                    .takeIf { turns.isNotEmpty() && it.isNotBlank() },
            )
        }
        return sessionId
    }

    private fun clearRetrying(turnId: Long) {
        val turns = currentState.turns
        val index = turns.indexOfFirst { it.id == turnId }
        if (index == -1) return
        if (turns[index].blocks.none { it is HomeChatBlock.Retrying }) return
        updateState {
            copy(
                turns = turns.toMutableList().also { list ->
                    list[index] = list[index].copy(
                        blocks = list[index].blocks.filterNot { it is HomeChatBlock.Retrying },
                    )
                },
            )
        }
    }

    private fun applyError(
        turnId: Long,
        message: String?,
        code: LlmErrorCode?,
        attempts: Int? = null,
    ) {
        updateTurn(turnId) {
            it.appendError(message, code, attempts)
        }
        updateState { copy(isGenerating = false, activeThinkingKey = null) }
    }

    private fun updateTurn(turnId: Long, transform: (HomeChatTurn) -> HomeChatTurn) {
        val currentTurns = currentState.turns
        val index = currentTurns.indexOfFirst { it.id == turnId }
        if (index == -1) {
            return
        }
        val updatedTurn = transform(currentTurns[index])
        updateState {
            copy(turns = currentTurns.toMutableList().also { it[index] = updatedTurn })
        }
    }

    /** 思考块按 Mapper 分配的回合内 id 全量替换：同 id 覆盖文本，新 id 按到达顺序追加（可穿插在工具/文本之间）。 */
    private fun HomeChatTurn.upsertThinking(id: Int, text: String): HomeChatTurn {
        if (text.isBlank()) return this
        val found = blocks.indexOfLast { it is HomeChatBlock.Thinking && it.id == id }
        return if (found != -1) {
            copy(
                blocks = blocks.toMutableList().also { mutableBlocks ->
                    mutableBlocks[found] =
                        (mutableBlocks[found] as HomeChatBlock.Thinking).copy(text = text)
                },
            )
        } else {
            copy(blocks = blocks + HomeChatBlock.Thinking(id = id, text = text))
        }
    }

    private fun HomeChatTurn.appendText(delta: String): HomeChatTurn {
        if (delta.isEmpty()) return this
        val lastBlock = blocks.lastOrNull()
        return if (lastBlock is HomeChatBlock.Text) {
            copy(blocks = blocks.dropLast(1) + lastBlock.copy(text = lastBlock.text + delta))
        } else {
            copy(blocks = blocks + HomeChatBlock.Text(delta))
        }
    }

    private fun HomeChatTurn.appendError(
        message: String?,
        code: LlmErrorCode?,
        attempts: Int? = null,
    ): HomeChatTurn {
        // message 为空也保留卡片：code 已承载错误类型，UI 按类型兕底文案
        return copy(blocks = blocks + HomeChatBlock.Error(message = message, code = code, attempts = attempts))
    }

    private fun HomeChatTurn.appendTool(
        call: ToolCallStatus,
        state: HomeToolState,
        resultText: String? = null,
        failedReason: String? = null,
        images: List<ContentBlock.Image> = emptyList(),
    ): HomeChatTurn = copy(
        blocks = blocks + HomeChatBlock.Tool(
            HomeToolStatus(
                callId = call.callId,
                name = call.label,
                state = state,
                resultText = resultText,
                failedReason = failedReason,
                displayNameRes = ToolPresentation.displayNameResOf(call.name),
                inputText = ToolPresentation.inputOf(call.name, call.argumentsJson),
                images = images.map {
                    HomeChatImage.of(it)
                },
            ),
        ),
    )

    private fun HomeChatTurn.updateTool(
        call: ToolCallStatus,
        state: HomeToolState,
        resultText: String? = null,
        failedReason: String? = null,
        images: List<ContentBlock.Image> = emptyList(),
    ): HomeChatTurn {
        val index = findToolBlockIndex(call.callId, call.label)
        if (index == -1) {
            return appendTool(call, state, resultText, failedReason, images)
        }
        return copy(
            blocks = blocks.toMutableList().also { mutableBlocks ->
                mutableBlocks[index] = HomeChatBlock.Tool(
                    HomeToolStatus(
                        callId = call.callId,
                        name = call.label,
                        state = state,
                        resultText = resultText,
                        failedReason = failedReason,
                        displayNameRes = ToolPresentation.displayNameResOf(call.name),
                        inputText = ToolPresentation.inputOf(call.name, call.argumentsJson),
                        images = images.map {
                            HomeChatImage.of(it)
                        },
                    ),
                )
            },
        )
    }

    private fun HomeChatTurn.findToolBlockIndex(callId: String?, label: String): Int {
        // 有 id 的调用只按 id 精确匹配：miss 即新调用（同名工具并发时不可互相覆盖），不落入无名兕底
        if (callId != null) {
            return blocks.indexOfLast { block ->
                block is HomeChatBlock.Tool && block.status.callId == callId
            }
        }
        // 无 id 的调用才按「无名块 + 同名」匹配（不提供 callId 的接入路径）
        return blocks.indexOfLast { block ->
            block is HomeChatBlock.Tool && block.status.callId == null && block.status.name == label
        }
    }

    private fun List<HomeChatTurn>.nextTurnId(): Long {
        return maxOfOrNull { it.id + 1 } ?: 0L
    }

    override fun onCleared() {
        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        super.onCleared()
    }

    private fun eventName(event: LlmStreamEvent): String = when (event) {
        LlmStreamEvent.RoundStarted -> "RoundStarted"
        is LlmStreamEvent.TextDelta -> "TextDelta"
        is LlmStreamEvent.Retrying -> "Retrying"
        is LlmStreamEvent.ThinkingStarted -> "ThinkingStarted"
        is LlmStreamEvent.ThinkingEnded -> "ThinkingEnded"
        is LlmStreamEvent.ToolRunning -> "ToolRunning"
        is LlmStreamEvent.ToolPending -> "ToolPending"
        is LlmStreamEvent.ToolSucceeded -> "ToolSucceeded"
        is LlmStreamEvent.ToolFailed -> "ToolFailed"
        is LlmStreamEvent.Error -> "Error"
        is LlmStreamEvent.Completed -> "Completed"
    }

    companion object {
        internal const val FAILED_REASON_INTERRUPTED = "Interrupted by user"
        private const val LOG_TAG = "niki914_nexus_HomeChatState"
    }
}
