package com.niki914.zafiro.app.ui.model.conversation

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.repo.PinnedConversation
import com.niki914.zafiro.repo.XRepo

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
    val errorMessage: String? = null,
    val deleteErrorMessage: String? = null,
    val activeConversationId: String? = null,
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val showBatchDeleteConfirmation: Boolean = false,
)

sealed interface ConversationHistoryIntent {
    data object Load : ConversationHistoryIntent
    data class SetActiveConversation(val id: String?) : ConversationHistoryIntent
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
}

sealed interface ConversationHistoryEffect {
    /** 打开某个会话；分叉产生的新会话也走这里。 */
    data class OpenConversation(val id: String) : ConversationHistoryEffect
    /** 删除的是当前活动会话，须交由 route 转给宿主（ZafiroApp）处理。 */
    data class DeleteActiveConversation(val id: String) : ConversationHistoryEffect
}

internal data class ConversationHistoryViewModelDependencies(
    val loadConversations: suspend () -> List<ConversationSummary>,
    val loadPinnedConversations: suspend () -> List<PinnedConversation>,
    val deleteConversation: suspend (String) -> Unit,
    val renameConversation: suspend (String, String) -> Unit,
    val forkConversation: suspend (String) -> String?,
    val setConversationPinned: suspend (String, Boolean) -> Unit,
) {
    companion object {
        val Default = ConversationHistoryViewModelDependencies(
            loadConversations = { ConversationRepo.listConversations() },
            loadPinnedConversations = { XRepo.pinnedConversations() },
            deleteConversation = { id -> ConversationRepo.deleteConversation(id) },
            renameConversation = { id, title -> ConversationRepo.renameConversation(id, title) },
            forkConversation = { id -> ConversationRepo.forkConversation(id) },
            setConversationPinned = { id, pinned -> XRepo.setConversationPinned(id, pinned) },
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

    override fun initUiState(): ConversationHistoryUiState =
        ConversationHistoryUiState(isLoading = true)

    override suspend fun handleIntent(intent: ConversationHistoryIntent) {
        when (intent) {
            ConversationHistoryIntent.Load -> load()
            is ConversationHistoryIntent.SetActiveConversation -> updateState {
                copy(activeConversationId = intent.id)
            }

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
        }
    }

    private fun toggleSelection(id: String) {
        updateState {
            copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
        }
    }

    private suspend fun load() {
        runCatching {
            dependencies.loadConversations() to dependencies.loadPinnedConversations()
        }.fold(
            onSuccess = { (conversations, pinnedConversations) ->
                updateState {
                    copy(
                        isLoading = false,
                        conversations = conversations,
                        pinnedConversations = pinnedConversations,
                        errorMessage = null,
                        deleteErrorMessage = null,
                    )
                }
            },
            onFailure = { throwable ->
                updateState {
                    copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: throwable::class.java.simpleName,
                    )
                }
            },
        )
    }

    private suspend fun delete(id: String) {
        updateState { copy(deleteErrorMessage = null) }
        if (id == currentState.activeConversationId) {
            sendEffect(ConversationHistoryEffect.DeleteActiveConversation(id))
            return
        }
        runCatching { dependencies.deleteConversation(id) }.fold(
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

    /** 删除成功后的收尾：解置顶再重载；活动会话由宿主删完后经同样路径收尾。 */
    private suspend fun afterDelete(id: String) {
        runCatching { dependencies.setConversationPinned(id, false) }
        load()
    }

    private suspend fun rename(id: String, title: String) {
        runCatching { dependencies.renameConversation(id, title) }.onSuccess {
            load()
        }
    }

    private suspend fun fork(id: String) {
        runCatching { dependencies.forkConversation(id) }.onSuccess { newId ->
            if (newId != null) {
                sendEffect(ConversationHistoryEffect.OpenConversation(newId))
            }
        }
    }

    private suspend fun setPinned(id: String, pinned: Boolean) {
        runCatching { dependencies.setConversationPinned(id, pinned) }.onSuccess {
            load()
        }
    }

    private suspend fun batchSetPinned(pinned: Boolean) {
        val ids = currentState.selectedIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            runCatching { dependencies.setConversationPinned(id, pinned) }
        }
        load()
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
            runCatching {
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
            sendEffect(ConversationHistoryEffect.DeleteActiveConversation(activeId))
        } else {
            load()
        }
    }
}
