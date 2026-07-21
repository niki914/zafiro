package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.lifecycle.viewModelScope
import com.niki914.nexus.agentic.app.conversation.ConversationFormatter
import com.niki914.nexus.agentic.app.conversation.ConversationRecord
import com.niki914.nexus.agentic.app.conversation.ConversationRepo
import com.niki914.nexus.agentic.chat.LLMController
import com.niki914.nexus.agentic.chat.LlmErrorCode
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.ComposeMVIViewModel
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.s3ss10n.ChatTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal interface HomeConversationStore {
    suspend fun lastOpenedConversationId(): String
    suspend fun setLastOpenedConversationId(value: String)
    suspend fun createConversation(firstUserInput: String): String
    suspend fun getConversation(id: String): ConversationRecord?
    suspend fun saveHistory(conversationId: String, history: List<ChatTurn>)
    suspend fun updateDraft(conversationId: String, draftText: String)
    suspend fun deleteConversation(id: String)
    suspend fun forkConversation(sourceId: String, keepTurnCount: Int): String
}

private object DefaultHomeConversationStore : HomeConversationStore {
    override suspend fun lastOpenedConversationId(): String = XRepo.lastOpenedConversationId()
    override suspend fun setLastOpenedConversationId(value: String) =
        XRepo.setLastOpenedConversationId(value)

    override suspend fun createConversation(firstUserInput: String): String {
        return ConversationRepo.createConversation(firstUserInput)
    }

    override suspend fun getConversation(
        id: String,
    ): ConversationRecord? {
        return ConversationRepo.getConversation(id)
    }

    override suspend fun saveHistory(conversationId: String, history: List<ChatTurn>) {
        ConversationRepo.saveHistory(conversationId = conversationId, history = history)
    }

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        ConversationRepo.updateDraft(conversationId = conversationId, draftText = draftText)
    }

    override suspend fun deleteConversation(id: String) {
        ConversationRepo.deleteConversation(id)
    }

    override suspend fun forkConversation(sourceId: String, keepTurnCount: Int): String {
        return ConversationRepo.forkConversation(sourceId, keepTurnCount)
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
)

sealed interface HomeChatBlock {
    data class Text(val text: String) : HomeChatBlock
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
    val lastEventName: String? = null,
    val streamEventCount: Int = 0,
    val currentConversationId: String? = null,
    val currentConversationTitle: String? = null,
    val expandedToolRunKeys: Set<String> = emptySet(),
    val expandedActionTurnId: Long? = null,
    val expandedActionSource: ActionSource? = null,
)

sealed interface HomeChatIntent {
    data class InputChanged(val value: String) : HomeChatIntent
    data object Send : HomeChatIntent
    data object StopGenerating : HomeChatIntent
    data object NewConversation : HomeChatIntent
    data class LoadConversation(val id: String) : HomeChatIntent
    data class DeleteConversation(val id: String) : HomeChatIntent
    data class ToggleToolRunExpanded(val turnId: Long, val runStartIndex: Int) : HomeChatIntent
    data class ToggleActionRow(val turnId: Long, val source: ActionSource) : HomeChatIntent
    data class ReGenerateAt(val turnId: Long) : HomeChatIntent
    data class ForkAt(val turnId: Long) : HomeChatIntent
}

internal interface HomeChatRuntime {
    fun stream(query: String): Flow<LlmStreamEvent>
    suspend fun resetConversation()
    suspend fun stopCurrentRound(keepCurrentTurn: Boolean = false)
    suspend fun getHistory(): List<ChatTurn>
    suspend fun replaceHistory(history: List<ChatTurn>)
}

private object LlmHomeChatRuntime : HomeChatRuntime {
    override fun stream(query: String): Flow<LlmStreamEvent> =
        LLMController.stream(query, runBlocking { ContextProvider.await() })

    override suspend fun resetConversation() = LLMController.resetConversation()
    override suspend fun stopCurrentRound(keepCurrentTurn: Boolean) =
        LLMController.stopCurrentRound(keepCurrentTurn)

    override suspend fun getHistory(): List<ChatTurn> = LLMController.getHistory()
    override suspend fun replaceHistory(history: List<ChatTurn>) =
        LLMController.replaceHistory(history)
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
            is HomeChatIntent.ToggleToolRunExpanded -> toggleToolRunExpanded(
                intent.turnId, intent.runStartIndex,
            )

            is HomeChatIntent.ToggleActionRow -> toggleActionRow(
                intent.turnId, intent.source,
            )

            is HomeChatIntent.ReGenerateAt -> reGenerateAt(intent.turnId)
            is HomeChatIntent.ForkAt -> forkAt(intent.turnId)
        }
    }

    private fun toggleToolRunExpanded(turnId: Long, runStartIndex: Int) {
        val key = "${turnId}_${runStartIndex}"
        updateState {
            copy(
                expandedToolRunKeys = if (key in expandedToolRunKeys) {
                    expandedToolRunKeys - key
                } else {
                    expandedToolRunKeys + key
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
        if (query.isBlank() || currentState.isGenerating) return

        val turnId = nextTurnId++
        updateState {
            copy(
                input = "",
                turns = turns + HomeChatTurn(id = turnId, userText = query),
                isGenerating = true,
                lastEventName = null,
                streamEventCount = 0,
            )
        }
        draftSaveJob?.cancel()
        draftSaveJob = null

        streamJob = viewModelScope.launch {
            try {
                val conversationId = ensureCurrentConversation(query)
                conversations.updateDraft(conversationId = conversationId, draftText = "")
                collectLlmStream(turnId = turnId, query = query)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
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
        runtime.stopCurrentRound(keepCurrentTurn = true)
        streamJob?.cancel()
        streamJob = null
        updateState { copy(isGenerating = false) }
    }

    private fun startNewConversation() {
        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        currentConversationId = null
        nextTurnId = 0L
        updateState { HomeChatUiState() }
        viewModelScope.launch {
            try {
                runtime.resetConversation()
                conversations.setLastOpenedConversationId("")
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

            is LlmStreamEvent.ToolRunning -> updateTurn(turnId) {
                it.appendTool(event.call.callId, event.call.label, HomeToolState.Running)
            }

            is LlmStreamEvent.ToolSucceeded -> updateTurn(turnId) {
                it.updateTool(event.call.callId, event.call.label, HomeToolState.Succeeded)
            }

            is LlmStreamEvent.ToolFailed -> updateTurn(turnId) {
                it.updateTool(event.call.callId, event.call.label, HomeToolState.Failed)
            }

            is LlmStreamEvent.Error -> {
                applyError(turnId = turnId, message = event.message, code = event.code)
            }

            is LlmStreamEvent.Completed -> {
                updateTurn(turnId) {
                    it.appendFinalText(event.fullText)
                }
                val completedConversationId = currentConversationId
                val completedHistory = if (completedConversationId != null) {
                    runtime.getHistory()
                } else {
                    emptyList()
                }
                if (completedConversationId != null) {
                    persistCompletedHistory(completedConversationId, completedHistory)
                }
                updateState { copy(isGenerating = false) }
            }
        }
    }

    private fun restoreLastConversationOnStartup() {
        if (startupRestoreAttempted) return
        startupRestoreAttempted = true
        viewModelScope.launch {
            try {
                val conversationId = conversations.lastOpenedConversationId()
                if (conversationId.isBlank()) return@launch
                val record = conversations.getConversation(conversationId) ?: return@launch
                runtime.replaceHistory(record.history)
                currentConversationId = conversationId
                val restoredTurns = ConversationFormatter.toHomeTurns(record.history)
                val restoredTitle = record.summary.title.takeIf {
                    restoredTurns.isNotEmpty() && it.isNotBlank()
                }
                nextTurnId = restoredTurns.nextTurnId()
                updateState {
                    copy(
                        input = record.draftText,
                        turns = restoredTurns,
                        isGenerating = false,
                        lastEventName = null,
                        streamEventCount = 0,
                        currentConversationId = conversationId,
                        currentConversationTitle = restoredTitle,
                        expandedToolRunKeys = emptySet(),
                        expandedActionTurnId = null,
                        expandedActionSource = null,
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    private suspend fun persistCompletedHistory(
        conversationId: String,
        history: List<ChatTurn>,
    ) {
        conversations.saveHistory(conversationId = conversationId, history = history)
        if (currentConversationId == conversationId) {
            conversations.setLastOpenedConversationId(conversationId)
        }
    }

    private suspend fun loadConversation(id: String) {
        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        val record = conversations.getConversation(id) ?: return
        runtime.replaceHistory(record.history)
        currentConversationId = id
        conversations.setLastOpenedConversationId(id)
        val restoredTurns = ConversationFormatter.toHomeTurns(record.history)
        val restoredTitle = record.summary.title.takeIf {
            restoredTurns.isNotEmpty() && it.isNotBlank()
        }
        nextTurnId = restoredTurns.nextTurnId()
        updateState {
            copy(
                input = "",
                turns = restoredTurns,
                isGenerating = false,
                lastEventName = null,
                streamEventCount = 0,
                currentConversationId = id,
                currentConversationTitle = restoredTitle,
                expandedToolRunKeys = emptySet(),
                expandedActionTurnId = null,
                expandedActionSource = null,
            )
        }
    }

    private fun findUserTurnIndex(history: List<ChatTurn>, targetTurnId: Long): Int {
        var userCount = 0L
        for ((index, turn) in history.withIndex()) {
            if (turn is ChatTurn.User) {
                if (userCount == targetTurnId) return index
                userCount++
            }
        }
        return -1
    }

    private fun findNextUserIndex(history: List<ChatTurn>, startIndex: Int): Int? {
        for (index in startIndex until history.size) {
            if (history[index] is ChatTurn.User) return index
        }
        return null
    }

    private suspend fun reGenerateAt(turnId: Long) {
        if (currentState.isGenerating) return
        val currentId = currentConversationId ?: return
        streamJob?.cancel()
        streamJob = null
        val history = runtime.getHistory()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val userTurn = history[userIndex] as? ChatTurn.User ?: return
        val userText = userTurn.content
        val newConvId = conversations.forkConversation(currentId, userIndex)
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
        val history = runtime.getHistory()
        val userIndex = findUserTurnIndex(history, turnId)
        if (userIndex < 0) return
        val nextUserIndex = findNextUserIndex(history, userIndex + 1)
        val endIndex = if (nextUserIndex != null) nextUserIndex - 1 else history.lastIndex
        val newConvId = conversations.forkConversation(currentId, endIndex + 1)
        loadConversation(newConvId)
    }

    internal suspend fun deleteConversationNow(id: String) {
        conversations.deleteConversation(id)
        if (id != currentConversationId) return

        streamJob?.cancel()
        streamJob = null
        draftSaveJob?.cancel()
        draftSaveJob = null
        currentConversationId = null
        nextTurnId = 0L
        runtime.resetConversation()
        conversations.setLastOpenedConversationId("")
        updateState { HomeChatUiState() }
    }

    private suspend fun ensureCurrentConversation(firstUserInput: String): String {
        currentConversationId?.let { return it }
        return conversations.createConversation(firstUserInput).also { id ->
            currentConversationId = id
            conversations.setLastOpenedConversationId(id)
            updateState {
                copy(
                    currentConversationId = id,
                    currentConversationTitle = ConversationFormatter
                        .titleFromFirstInput(firstUserInput)
                        .takeIf { turns.isNotEmpty() && it.isNotBlank() },
                )
            }
        }
    }

    private fun applyError(turnId: Long, message: String, code: LlmErrorCode?) {
        updateTurn(turnId) {
            it.appendError(message, code)
        }
        updateState { copy(isGenerating = false) }
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

    private fun HomeChatTurn.appendText(delta: String): HomeChatTurn {
        if (delta.isEmpty()) return this
        val lastBlock = blocks.lastOrNull()
        return if (lastBlock is HomeChatBlock.Text) {
            copy(blocks = blocks.dropLast(1) + lastBlock.copy(text = lastBlock.text + delta))
        } else {
            copy(blocks = blocks + HomeChatBlock.Text(delta))
        }
    }

    private fun HomeChatTurn.appendFinalText(fullText: String): HomeChatTurn {
        val displayedText =
            blocks.filterIsInstance<HomeChatBlock.Text>().joinToString(separator = "") { it.text }
        val delta = fullText.removePrefix(displayedText)
        return appendText(delta)
    }

    private fun HomeChatTurn.appendError(message: String, code: LlmErrorCode?): HomeChatTurn {
        if (message.isBlank()) return this
        return copy(blocks = blocks + HomeChatBlock.Error(message = message, code = code))
    }

    private fun HomeChatTurn.appendTool(
        callId: String?,
        label: String,
        state: HomeToolState,
    ): HomeChatTurn = copy(
        blocks = blocks + HomeChatBlock.Tool(
            HomeToolStatus(
                callId = callId,
                name = label,
                state = state,
            ),
        ),
    )

    private fun HomeChatTurn.updateTool(
        callId: String?,
        label: String,
        state: HomeToolState,
    ): HomeChatTurn {
        val index = blocks.indexOfLast { block ->
            block is HomeChatBlock.Tool && block.status.matchesTool(callId, label)
        }
        if (index == -1) {
            return appendTool(callId, label, state)
        }
        return copy(
            blocks = blocks.toMutableList().also { mutableBlocks ->
                mutableBlocks[index] = HomeChatBlock.Tool(
                    HomeToolStatus(
                        callId = callId,
                        name = label,
                        state = state,
                    ),
                )
            },
        )
    }

    private fun HomeToolStatus.matchesTool(callId: String?, label: String): Boolean {
        return if (callId != null || this.callId != null) {
            this.callId == callId
        } else {
            name == label
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
        is LlmStreamEvent.ToolRunning -> "ToolRunning"
        is LlmStreamEvent.ToolSucceeded -> "ToolSucceeded"
        is LlmStreamEvent.ToolFailed -> "ToolFailed"
        is LlmStreamEvent.Error -> "Error"
        is LlmStreamEvent.Completed -> "Completed"
    }
}
