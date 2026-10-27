package com.niki914.zafiro.app.ui.model.conversation

import androidx.lifecycle.viewModelScope
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.api.util.Debouncer
import com.niki914.zafiro.api.util.debouncer
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.api.util.tryOrDefault
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ConversationSearchRepo
import com.niki914.zafiro.app.conversation.ConversationSearchResult
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.repo.PinnedConversation
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 历史对话页的界面状态。
 *
 * [activeConversationId] 由 route 经 [ConversationHistoryIntent.SetActiveConversation] 同步进来：
 * 它既驱动活动行的高亮脉冲，也决定删除 / 重命名走「当前活动会话」的宿主通路还是普通仓储通路。
 */
data class ConversationHistoryUiState(
    val isLoading: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val pinnedConversations: List<PinnedConversation> = emptyList(),
    val runStates: Map<String, ConversationRunState> = emptyMap(),
    val errorMessage: String? = null,
    val deleteErrorMessage: String? = null,
    val activeConversationId: String? = null,
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val showBatchDeleteConfirmation: Boolean = false,
    /** 搜索框是否处于激活展开态。 */
    val isSearchActive: Boolean = false,
    /** 搜索输入框的当前文本。非空时列表切换为搜索结果模式。 */
    val searchQuery: String = "",
    /** 搜索异步执行中（防抖后发起、结果到达前）。 */
    val isSearching: Boolean = false,
    /** 搜索结果列表；[searchQuery] 为空时始终为空。 */
    val searchResults: List<ConversationSearchResult> = emptyList(),
)

sealed interface ConversationHistoryIntent {
    data object Load : ConversationHistoryIntent
    data class SetActiveConversation(val id: String?) : ConversationHistoryIntent
    data class ClearUnread(val id: String) : ConversationHistoryIntent
    data object EnterSelection : ConversationHistoryIntent
    data object ExitSelection : ConversationHistoryIntent
    data class ToggleSelection(val id: String) : ConversationHistoryIntent
    data class Delete(val id: String) : ConversationHistoryIntent
    data class Rename(val id: String, val title: String) : ConversationHistoryIntent
    data class Fork(val id: String) : ConversationHistoryIntent
    data class SetPinned(val id: String, val pinned: Boolean) : ConversationHistoryIntent
    data class BatchSetPinned(val pinned: Boolean) : ConversationHistoryIntent
    data object RequestBatchDelete : ConversationHistoryIntent
    data object DismissBatchDeleteConfirmation : ConversationHistoryIntent
    data object ConfirmBatchDelete : ConversationHistoryIntent

    /** 宿主已删除活动会话：route 转发 [ConversationHistoryEffect.DeleteActiveConversation] 后回报结果。 */
    data class ActiveConversationDeleted(val id: String) : ConversationHistoryIntent

    /** 会话删除失败（含活动会话删除失败）。 */
    data class DeleteFailed(val message: String) : ConversationHistoryIntent

    /** 搜索输入文本变更：立即更新输入框文本，防抖后异步查询。 */
    data class UpdateSearchQuery(val query: String) : ConversationHistoryIntent

    /** 进入搜索激活态。 */
    data object EnterSearch : ConversationHistoryIntent

    /** 退出搜索模式并清空状态。 */
    data object ExitSearch : ConversationHistoryIntent

    /** 清空搜索：取消在途搜索任务，重置搜索状态。 */
    data object ClearSearch : ConversationHistoryIntent
}

sealed interface ConversationHistoryEffect {
    /** 打开某个会话；分叉产生的新会话也走这里。 */
    data class OpenConversation(val id: String) : ConversationHistoryEffect

    /** 删除的是当前活动会话，须交由 route 转给宿主（ZafiroApp）处理。 */
    data class DeleteActiveConversation(val id: String) : ConversationHistoryEffect
}

internal data class ConversationHistoryViewModelDependencies(
    val observeConversations: () -> Flow<List<ConversationSummary>>,
    val loadPinnedConversations: suspend () -> List<PinnedConversation>,
    val deleteConversation: suspend (String) -> Unit,
    val renameConversation: suspend (String, String) -> Unit,
    val forkConversation: suspend (String) -> String?,
    val setConversationPinned: suspend (String, Boolean) -> Unit,
    val searchConversations: suspend (String) -> List<ConversationSearchResult>,
    val destroyAgent: (String) -> Unit = { id ->
        tryIgnore {
            requireService<AgentManager>().destroy(
                id
            )
        }
    },
    val setHasUnread: suspend (String, Boolean) -> Unit = { id, unread ->
        ConversationRepo.setHasUnread(id, unread)
    },
    val observeActiveStates: () -> Flow<Map<String, AgentState>> = {
        tryOrDefault(MutableStateFlow(emptyMap())) { requireService<AgentManager>().activeStates }
    },
) {
    companion object {
        val Default = ConversationHistoryViewModelDependencies(
            observeConversations = { ConversationRepo.observeConversations() },
            loadPinnedConversations = { XRepo.pinnedConversations() },
            deleteConversation = { id -> ConversationRepo.deleteConversation(id) },
            renameConversation = { id, title -> ConversationRepo.renameConversation(id, title) },
            forkConversation = { id -> ConversationRepo.forkConversation(id) },
            setConversationPinned = { id, pinned -> XRepo.setConversationPinned(id, pinned) },
            searchConversations = { query -> ConversationSearchRepo.searchConversations(query) },
            destroyAgent = { id -> tryIgnore { requireService<AgentManager>().destroy(id) } },
            setHasUnread = { id, unread -> ConversationRepo.setHasUnread(id, unread) },
            observeActiveStates = {
                tryOrDefault(MutableStateFlow(emptyMap())) { requireService<AgentManager>().activeStates }
            },
        )
    }
}

class ConversationHistoryViewModel internal constructor(
    private val dependencies: ConversationHistoryViewModelDependencies,
) : ComposeMVIViewModel<
        ConversationHistoryIntent,
        ConversationHistoryUiState,
        ConversationHistoryEffect,
        >() {

    constructor() : this(ConversationHistoryViewModelDependencies.Default)

    private val searchDebouncer: Debouncer = viewModelScope.debouncer(250L)

    override fun initUiState(): ConversationHistoryUiState =
        ConversationHistoryUiState(isLoading = true)

    override suspend fun handleIntent(intent: ConversationHistoryIntent) {
        when (intent) {
            ConversationHistoryIntent.Load -> load()
            is ConversationHistoryIntent.SetActiveConversation -> updateState {
                copy(activeConversationId = intent.id)
            }

            is ConversationHistoryIntent.ClearUnread -> clearUnread(intent.id)
            ConversationHistoryIntent.EnterSelection -> updateState { copy(selecting = true) }
            ConversationHistoryIntent.ExitSelection -> updateState {
                copy(
                    selecting = false,
                    selectedIds = emptySet(),
                    showBatchDeleteConfirmation = false,
                )
            }

            is ConversationHistoryIntent.ToggleSelection -> toggleSelection(intent.id)
            is ConversationHistoryIntent.Delete -> delete(intent.id)
            is ConversationHistoryIntent.Rename -> rename(intent.id, intent.title)
            is ConversationHistoryIntent.Fork -> fork(intent.id)
            is ConversationHistoryIntent.SetPinned -> setPinned(intent.id, intent.pinned)
            is ConversationHistoryIntent.BatchSetPinned -> batchSetPinned(intent.pinned)
            ConversationHistoryIntent.RequestBatchDelete -> requestBatchDelete()
            ConversationHistoryIntent.DismissBatchDeleteConfirmation -> dismissBatchDeleteConfirmation()
            ConversationHistoryIntent.ConfirmBatchDelete -> confirmBatchDelete()
            is ConversationHistoryIntent.ActiveConversationDeleted -> afterDelete(intent.id)
            is ConversationHistoryIntent.DeleteFailed -> updateState {
                copy(deleteErrorMessage = intent.message)
            }

            is ConversationHistoryIntent.UpdateSearchQuery -> updateSearchQuery(intent.query)
            ConversationHistoryIntent.EnterSearch -> enterSearch()
            ConversationHistoryIntent.ExitSearch -> exitSearch()
            ConversationHistoryIntent.ClearSearch -> exitSearch()
        }
    }

    private fun enterSearch() {
        updateState { copy(isSearchActive = true) }
    }

    private fun exitSearch() {
        searchDebouncer.cancel()
        updateState {
            copy(
                isSearchActive = false,
                searchQuery = "",
                isSearching = false,
                searchResults = emptyList(),
            )
        }
    }

    private fun toggleSelection(id: String) {
        updateState {
            copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
        }
    }

    private fun updateSearchQuery(query: String) {
        updateState { copy(searchQuery = query) }
        if (query.isBlank()) {
            searchDebouncer.cancel()
            updateState { copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        updateState { copy(isSearching = true) }
        searchDebouncer.debounce {
            val results = dependencies.searchConversations(query)
            // 防抖回调到达时，若用户已清空搜索则丢弃结果
            if (currentState.searchQuery == query) {
                updateState { copy(isSearching = false, searchResults = results) }
            }
        }
    }


    private var observeJob: Job? = null

    private fun load() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                dependencies.observeConversations().distinctUntilChanged(),
                dependencies.observeActiveStates(),
            ) { conversations, activeStates ->
                conversations to activeStates
            }
                .catch { throwable ->
                    updateState {
                        copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: throwable::class.java.simpleName,
                        )
                    }
                }
                .collect { (conversations, activeStates) ->
                    val runStates = calculateRunStates(
                        conversations = conversations,
                        activeStates = activeStates,
                    )
                    val pinnedConversations = loadPinnedSafely()
                    updateState {
                        copy(
                            isLoading = false,
                            conversations = conversations,
                            pinnedConversations = pinnedConversations,
                            runStates = runStates,
                            errorMessage = null,
                            deleteErrorMessage = null,
                        )
                    }
                }
        }
    }

    private fun calculateRunStates(
        conversations: List<ConversationSummary>,
        activeStates: Map<String, AgentState>,
    ): Map<String, ConversationRunState> {
        val result = mutableMapOf<String, ConversationRunState>()
        for (summary in conversations) {
            val state = activeStates[summary.id]
            val runState = when {
                state is AgentState.WaitingApproval -> ConversationRunState.WaitingApproval
                state?.isRunning == true -> ConversationRunState.Running
                summary.hasUnread -> ConversationRunState.UnreadCompleted
                else -> ConversationRunState.Idle
            }
            if (runState != ConversationRunState.Idle) {
                result[summary.id] = runState
            }
        }
        return result
    }

    private suspend fun clearUnread(id: String) {
        tryIgnore { dependencies.setHasUnread(id, false) }
    }

    private suspend fun loadPinnedSafely(): List<PinnedConversation> =
        tryOrDefault(emptyList()) { dependencies.loadPinnedConversations() }

    private suspend fun refreshPinned() {
        val pinned = loadPinnedSafely()
        updateState { copy(pinnedConversations = pinned) }
    }

    private suspend fun delete(id: String) {
        updateState { copy(deleteErrorMessage = null) }
        tryIgnore { dependencies.destroyAgent(id) }
        if (id == currentState.activeConversationId) {
            sendEffect(ConversationHistoryEffect.DeleteActiveConversation(id))
            return
        }
        runCatchingCancellable { dependencies.deleteConversation(id) }.fold(
            onSuccess = { afterDelete(id) },
            onFailure = { throwable ->
                updateState {
                    copy(
                        deleteErrorMessage = throwable.message ?: throwable::class.java.simpleName,
                    )
                }
            },
        )
    }

    /** 删除成功后的收尾：解置顶；活动会话由宿主删完后经同样路径收尾。列表行变动由 Room Flow 自动推送。 */
    private suspend fun afterDelete(id: String) {
        tryIgnore { dependencies.setConversationPinned(id, false) }
        refreshPinned()
    }

    private suspend fun rename(id: String, title: String) {
        tryIgnore { dependencies.renameConversation(id, title) }
    }

    private suspend fun fork(id: String) {
        runCatchingCancellable { dependencies.forkConversation(id) }.onSuccess { newId ->
            if (newId != null) {
                sendEffect(ConversationHistoryEffect.OpenConversation(newId))
            }
        }
    }

    private suspend fun setPinned(id: String, pinned: Boolean) {
        runCatchingCancellable { dependencies.setConversationPinned(id, pinned) }.onSuccess {
            refreshPinned()
        }
    }

    private suspend fun batchSetPinned(pinned: Boolean) {
        val ids = currentState.selectedIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            tryIgnore { dependencies.setConversationPinned(id, pinned) }
        }
        refreshPinned()
    }

    private fun requestBatchDelete() {
        if (currentState.selectedIds.isEmpty()) return
        updateState { copy(showBatchDeleteConfirmation = true) }
    }

    private fun dismissBatchDeleteConfirmation() {
        updateState { copy(showBatchDeleteConfirmation = false) }
    }

    private suspend fun confirmBatchDelete() {
        val targetIds = currentState.selectedIds
        if (targetIds.isEmpty()) {
            updateState { copy(showBatchDeleteConfirmation = false) }
            return
        }
        val activeId = currentState.activeConversationId
        val deleteActive = activeId != null && activeId in targetIds
        val normalIds = if (deleteActive) targetIds - activeId else targetIds

        updateState {
            copy(
                selecting = false,
                selectedIds = emptySet(),
                showBatchDeleteConfirmation = false,
                deleteErrorMessage = null,
            )
        }

        var failureMessage: String? = null
        normalIds.forEach { id ->
            runCatchingCancellable {
                dependencies.destroyAgent(id)
                dependencies.deleteConversation(id)
                dependencies.setConversationPinned(id, false)
            }.onFailure { throwable ->
                if (failureMessage == null) {
                    failureMessage = throwable.message ?: throwable::class.java.simpleName
                }
            }
        }

        if (failureMessage != null) {
            updateState { copy(deleteErrorMessage = failureMessage) }
        }

        if (deleteActive) {
            tryIgnore { dependencies.destroyAgent(activeId) }
            sendEffect(ConversationHistoryEffect.DeleteActiveConversation(activeId))
        } else {
            refreshPinned()
        }
    }
}
