package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import com.niki914.zafiro.repo.XRepo
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal interface HomeConversationStore {
    suspend fun lastOpenedConversationId(): String
    suspend fun setLastOpenedConversationId(value: String)
    suspend fun loadLastConversationOnStartup(): Boolean
    suspend fun createConversation(id: String, firstUserInput: String)
    suspend fun getConversation(id: String): ConversationRecord?
    suspend fun updateDraft(conversationId: String, draftText: String)
    suspend fun deleteConversation(id: String)
    suspend fun forkConversation(sourceId: String, keepEntryCount: Int, kind: ForkKind): String
}

private object DefaultHomeConversationStore : HomeConversationStore {
    override suspend fun lastOpenedConversationId(): String = XRepo.lastOpenedConversationId()
    override suspend fun setLastOpenedConversationId(value: String) =
        XRepo.setLastOpenedConversationId(value)

    override suspend fun loadLastConversationOnStartup(): Boolean =
        XRepo.loadLastConversationOnStartup()

    override suspend fun createConversation(id: String, firstUserInput: String) {
        ConversationRepo.createConversation(id = id, firstUserInput = firstUserInput)
    }

    override suspend fun getConversation(
        id: String,
    ): ConversationRecord? {
        return ConversationRepo.getConversation(id)
    }

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        ConversationRepo.updateDraft(conversationId = conversationId, draftText = draftText)
    }

    override suspend fun deleteConversation(id: String) {
        ConversationRepo.deleteConversation(id)
    }

    override suspend fun forkConversation(
        sourceId: String,
        keepEntryCount: Int,
        kind: ForkKind,
    ): String {
        return ConversationRepo.forkConversation(
            sourceId = sourceId,
            keepEntryCount = keepEntryCount,
            kind = kind,
        )
    }
}

enum class ActionSource { User, Agent }

enum class HomeToolState {
    Running,
    Succeeded,
    Failed,
}

data class HomeToolStatus(
    val callId: String? = null,
    val name: String,
    val state: HomeToolState,
    val resultText: String? = null,
    val failedReason: String? = null,
    /** 本地化显示名 res id；null → 回退 [name]（Custom Tool / MCP）。 */
    val displayNameRes: Int? = null,
    /** 工具参数原文（复制用）；显示预览由 UI 从原文裁剪。null → 只显示标题无预览、无复制。 */
    val inputText: String? = null,
)

sealed interface HomeChatBlock {
    data class Text(val text: String) : HomeChatBlock
    data class Thinking(val id: Int, val text: String) : HomeChatBlock
    data class Tool(val status: HomeToolStatus) : HomeChatBlock
    data class Error(val message: String, val code: LlmErrorCode? = null) : HomeChatBlock
}

data class HomeChatTurn(
    val id: Long,
    val userText: String,
    val blocks: List<HomeChatBlock> = emptyList(),
)

data class HomeChatUiState(
    val input: String = "",
    val turns: List<HomeChatTurn> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingConversation: Boolean = false,
    val lastEventName: String? = null,
    val streamEventCount: Int = 0,
    val currentConversationId: String? = null,
    val currentConversationTitle: String? = null,
    val expandedToolRuns: Set<String> = emptySet(),
    val expandedToolResults: Set<String> = emptySet(),
    val expandedThinking: Set<String> = emptySet(),
    val expandedActionTurnId: Long? = null,
    val expandedActionSource: ActionSource? = null,
    /** 当前正在流式产生的思考块 key；仅驱动 thinking 块内滚动跟随。 */
    val activeThinkingKey: String? = null,
    /**
     * 已自动展开过的思考块身份（"${turnId}_t${thinkingId}"）。
     * Mapper 对 Delta 续接也重发 ThinkingStarted（同 id），此集合区分「新块首发」与「续接回声」：
     * 首发自动展开；回声不重复展开，用户手动收起后不被续接重新撑开。
     */
    val autoExpandedThinking: Set<String> = emptySet(),
)

/**
 * 会话切换时的统一瞬态清理：三组展开态 + 操作行 + active thinking 指针 + 自动展开记录全清，
 * 新会话不复用任何展开状态。所有会话切换路径（restore/load/new/delete）统一调用。
 */
fun HomeChatUiState.withClearedTransient() = copy(
    expandedToolRuns = emptySet(),
    expandedToolResults = emptySet(),
    expandedThinking = emptySet(),
    expandedActionTurnId = null,
    expandedActionSource = null,
    activeThinkingKey = null,
    autoExpandedThinking = emptySet(),
)

sealed interface HomeChatIntent {
    data class InputChanged(val value: String) : HomeChatIntent
    data object Send : HomeChatIntent
    data object StopGenerating : HomeChatIntent
    data object NewConversation : HomeChatIntent
    data class LoadConversation(val id: String) : HomeChatIntent
    data class DeleteConversation(val id: String) : HomeChatIntent
    data class ToggleToolRun(val turnId: Long, val runStartIndex: Int) : HomeChatIntent
    data class ToggleToolResult(val turnId: Long, val runStartIndex: Int, val toolIndex: Int) : HomeChatIntent
    data class ToggleThinking(val turnId: Long, val blockIndex: Int) : HomeChatIntent
    data class ToggleActionRow(val turnId: Long, val source: ActionSource) : HomeChatIntent
    data class ReGenerateAt(val turnId: Long) : HomeChatIntent
    data class ForkAt(val turnId: Long) : HomeChatIntent
}

internal interface HomeChatRuntime {
    fun stream(query: String): Flow<LlmStreamEvent>
    suspend fun resetConversation()
    suspend fun stopCurrentRound()
    suspend fun ensureSession(): String
    suspend fun openSession(restore: SessionSnapshot)
    suspend fun historySnapshot(): List<Message>
}

private object LlmHomeChatRuntime : HomeChatRuntime {
    override fun stream(query: String): Flow<LlmStreamEvent> =
        LLMController.stream(query, runBlocking { ContextProvider.await() })

    override suspend fun resetConversation() = LLMController.resetConversation()
    override suspend fun stopCurrentRound() =
        LLMController.stopCurrentRound()

    override suspend fun ensureSession(): String = LLMController.ensureSession()
    override suspend fun openSession(restore: SessionSnapshot) =
        LLMController.openSession(restore)

    override suspend fun historySnapshot(): List<Message> = LLMController.historySnapshot()
}

class HomeChatViewModel internal constructor(
    private val runtime: HomeChatRuntime = LlmHomeChatRuntime,
    private val conversations: HomeConversationStore = DefaultHomeConversationStore,
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

    private suspend fun sendCurrentInput() {
        val query = currentState.input.trim()
        if (query.isBlank() || currentState.isGenerating) {
            Logger.d(
                LOG_TAG,
                "send skipped blank=${query.isBlank()} isGenerating=${currentState.isGenerating}"
            )
            return
        }
        Logger.i(LOG_TAG, "send requested queryLength=${query.length}")

        val turnId = nextTurnId++
        updateState {
            copy(
                input = "",
                // 新回合开始：清除旧错误卡片（瞬态 UI 态，T3 TODO②——
                // 错误只在当轮显示，下一轮发起即消失）
                turns = turns.map { turn ->
                    turn.copy(blocks = turn.blocks.filterNot { it is HomeChatBlock.Error })
                } + HomeChatTurn(id = turnId, userText = query),
                isGenerating = true,
                lastEventName = null,
                streamEventCount = 0,
                activeThinkingKey = null,
                autoExpandedThinking = emptySet(),
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
                collectLlmStream(turnId = turnId, query = query)
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

    private suspend fun collectLlmStream(turnId: Long, query: String) {
        runtime.stream(query).collect { event ->
            val eventName = eventName(event)
            val eventCount = currentState.streamEventCount + 1
            updateState {
                copy(
                    lastEventName = eventName,
                    streamEventCount = eventCount,
                )
            }
            applyEvent(turnId = turnId, event = event)
        }
    }

    private suspend fun applyEvent(turnId: Long, event: LlmStreamEvent) {
        when (event) {
            LlmStreamEvent.RoundStarted -> updateState { copy(isGenerating = true) }
            is LlmStreamEvent.TextDelta -> updateTurn(turnId) {
                it.appendText(event.delta)
            }

            is LlmStreamEvent.ThinkingStarted -> {
                // 首发自动展开 + 置 active；续接回声（同块 Delta 重发同 id）不重复展开，
                // 手动收起后不复活；块完成后保持展开不收起（见 withClearedTransient）
                updateState {
                    val turnIndex = turns.indexOfFirst { it.id == turnId }
                    if (turnIndex == -1) return@updateState this
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
                    val blockId = "${turnId}_t${event.id}"
                    copy(
                        turns = turns.toMutableList().also { it[turnIndex] = updated },
                        expandedThinking = if (blockId in autoExpandedThinking) {
                            expandedThinking
                        } else {
                            expandedThinking + key
                        },
                        autoExpandedThinking = autoExpandedThinking + blockId,
                        activeThinkingKey = key,
                    )
                }
            }

            is LlmStreamEvent.ThinkingEnded -> {
                updateTurn(turnId) { it.upsertThinking(event.id, event.text) }
                // 思考结束：只摘 active 指针（停止滚动跟随），块保持展开不收起
                updateState { copy(activeThinkingKey = null) }
            }

            is LlmStreamEvent.ToolRunning -> updateTurn(turnId) {
                it.appendTool(event.call, HomeToolState.Running)
            }

            is LlmStreamEvent.ToolSucceeded -> updateTurn(turnId) {
                it.updateTool(
                    event.call,
                    HomeToolState.Succeeded, event.outputText,
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
                applyError(turnId = turnId, message = event.message, code = event.code)
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
                turns = turns + HomeChatTurn(id = newTurnId, userText = userText),
                isGenerating = true,
                lastEventName = null,
                streamEventCount = 0,
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
        streamJob = viewModelScope.launch {
            try {
                collectLlmStream(turnId = newTurnId, query = userText)
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

    private fun applyError(turnId: Long, message: String, code: LlmErrorCode?) {
        updateTurn(turnId) {
            it.appendError(message, code)
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
                    mutableBlocks[found] = (mutableBlocks[found] as HomeChatBlock.Thinking).copy(text = text)
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

    private fun HomeChatTurn.appendError(message: String, code: LlmErrorCode?): HomeChatTurn {
        if (message.isBlank()) return this
        return copy(blocks = blocks + HomeChatBlock.Error(message = message, code = code))
    }

    private fun HomeChatTurn.appendTool(
        call: ToolCallStatus,
        state: HomeToolState,
        resultText: String? = null,
        failedReason: String? = null,
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
            ),
        ),
    )

    private fun HomeChatTurn.updateTool(
        call: ToolCallStatus,
        state: HomeToolState,
        resultText: String? = null,
        failedReason: String? = null,
    ): HomeChatTurn {
        val index = findToolBlockIndex(call.callId, call.label)
        if (index == -1) {
            return appendTool(call, state, resultText, failedReason)
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
                    ),
                )
            },
        )
    }

    private fun HomeChatTurn.findToolBlockIndex(callId: String?, label: String): Int {
        if (callId != null) {
            val exactMatch = blocks.indexOfLast { block ->
                block is HomeChatBlock.Tool && block.status.callId == callId
            }
            if (exactMatch != -1) return exactMatch
        }
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
        is LlmStreamEvent.ThinkingStarted -> "ThinkingStarted"
        is LlmStreamEvent.ThinkingEnded -> "ThinkingEnded"
        is LlmStreamEvent.ToolRunning -> "ToolRunning"
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
