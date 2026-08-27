package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingItem
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingsManager
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.BuiltinToolGroupMode
import com.niki914.zafiro.repo.BuiltinToolGroups
import kotlinx.coroutines.CancellationException

// 一级页组行：组状态由成员派生，永不落盘。
data class BuiltinToolGroupUiItem(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val mode: BuiltinToolGroupMode,
    // WHOLE：全开=true、任一关=false；PER_TOOL 无一级页开关，恒 true（不参与渲染判断）
    val checked: Boolean,
)

data class BuiltinToolSettingsUiState(
    val groups: List<BuiltinToolGroupUiItem> = emptyList(),
    val standaloneTools: List<BuiltinToolSettingItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    @param:StringRes val descriptionResId: Int = R.string.builtin_tool_loading,
    val descriptionArg: String? = null,
)

sealed interface BuiltinToolSettingsIntent {
    data object Load : BuiltinToolSettingsIntent

    /** 单独工具开关（一级页与二级页共用单工具写入路径） */
    data class ItemEnabledChanged(
        val name: String,
        val value: Boolean,
    ) : BuiltinToolSettingsIntent

    /** WHOLE 组写穿 */
    data class GroupToggled(
        val groupId: String,
        val value: Boolean,
    ) : BuiltinToolSettingsIntent
}

sealed interface BuiltinToolSettingsEffect

class BuiltinToolSettingsViewModel :
    ComposeMVIViewModel<
            BuiltinToolSettingsIntent,
            BuiltinToolSettingsUiState,
            BuiltinToolSettingsEffect,
            >() {

    private val manager = BuiltinToolSettingsManager()

    override fun initUiState(): BuiltinToolSettingsUiState = BuiltinToolSettingsUiState()

    private companion object {
        private const val LOG_TAG = "niki914_nexus_BuiltinToolSettingsViewModel"
    }

    override suspend fun handleIntent(intent: BuiltinToolSettingsIntent) {
        when (intent) {
            BuiltinToolSettingsIntent.Load -> load()
            is BuiltinToolSettingsIntent.ItemEnabledChanged -> setEnabled(
                name = intent.name,
                enabled = intent.value,
            )
            is BuiltinToolSettingsIntent.GroupToggled -> setGroupEnabled(
                groupId = intent.groupId,
                enabled = intent.value,
            )
        }
    }

    private suspend fun load() {
        updateState {
            copy(
                isLoading = true,
                descriptionResId = R.string.builtin_tool_loading,
                descriptionArg = null,
            )
        }
        runCatching {
            manager.load()
        }.onSuccess { loadedItems ->
            Logger.d(LOG_TAG, "load tools=${loadedItems.size}")
            applyLoadedItems(
                loadedItems = loadedItems,
                baseState = currentState,
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    descriptionResId = R.string.builtin_tool_load_failed,
                    descriptionArg = throwable.message ?: throwable::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun setEnabled(name: String, enabled: Boolean) {
        val fallback = currentState
        val updatedTools = fallback.standaloneTools.map { item ->
            if (item.name == name) item.copy(enabled = enabled) else item
        }
        updateState { copy(standaloneTools = updatedTools, isSaving = true) }
        persist(
            fallback = fallback.copy(standaloneTools = updatedTools),
            write = { manager.setEnabled(name, enabled) },
            failLog = { reason -> Logger.w(LOG_TAG, "setEnabled failed tool=$name reason=$reason") },
        )
    }

    private suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
        val fallback = currentState
        val updatedGroups = fallback.groups.map { item ->
            if (item.id == groupId) item.copy(checked = enabled) else item
        }
        updateState { copy(groups = updatedGroups, isSaving = true) }
        persist(
            fallback = fallback.copy(groups = updatedGroups),
            write = { manager.setGroupEnabled(groupId, enabled) },
            failLog = { reason ->
                Logger.w(LOG_TAG, "setGroupEnabled failed group=$groupId reason=$reason")
            },
        )
    }

    private suspend fun persist(
        fallback: BuiltinToolSettingsUiState,
        write: suspend () -> BuiltinToolResult,
        failLog: (String?) -> Unit,
    ) {
        runCatching {
            write()
        }.onSuccess { result ->
            if (result.ok) {
                refreshAfterSave(fallback = fallback)
            } else {
                failLog(result.message)
                rollbackTo(fallback = fallback, error = result.message)
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            failLog(throwable.message)
            rollbackTo(
                fallback = fallback,
                error = throwable.message ?: throwable::class.java.simpleName,
            )
        }
    }

    private suspend fun refreshAfterSave(fallback: BuiltinToolSettingsUiState) {
        runCatching {
            manager.load()
        }.onSuccess { loadedItems ->
            applyLoadedItems(loadedItems = loadedItems, baseState = fallback)
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            applyLoadedItemsFallback(fallback = fallback)
        }
    }

    private fun applyLoadedItems(
        loadedItems: List<BuiltinToolSettingItem>,
        baseState: BuiltinToolSettingsUiState,
    ): Unit = withSynthesizedState(baseState, loadedItems) { state ->
        updateState { state }
    }

    private fun applyLoadedItemsFallback(fallback: BuiltinToolSettingsUiState) =
        withSynthesizedState(
            baseState = fallback,
            loadedItems = null,
        ) { state ->
            updateState { state }
        }

    private fun rollbackTo(fallback: BuiltinToolSettingsUiState, error: String?) {
        updateState {
            fallback.copy(
                isSaving = false,
                isLoading = false,
                descriptionResId = R.string.builtin_tool_save_failed,
                descriptionArg = error,
            )
        }
    }

    private inline fun withSynthesizedState(
        baseState: BuiltinToolSettingsUiState,
        loadedItems: List<BuiltinToolSettingItem>?,
        commit: (BuiltinToolSettingsUiState) -> Unit,
    ) {
        val byName = loadedItems?.associateBy { it.name }
        val groups = BuiltinToolGroups.all.map { group ->
            BuiltinToolGroupUiItem(
                id = group.id,
                titleRes = group.titleRes,
                summaryRes = group.summaryRes,
                mode = group.mode,
                checked = group.members.all { byName?.get(it)?.enabled == true },
            )
        }
        val groupedNames = BuiltinToolGroups.all.flatMap { it.members }.toSet()
        val standalone = when {
            byName != null -> byName.values
                .filter { it.name !in groupedNames }
                .sortedBy { it.name }
            else -> baseState.standaloneTools
        }
        val isEmpty = groups.isEmpty() && standalone.isEmpty()
        commit(
            baseState.copy(
                groups = groups,
                standaloneTools = standalone,
                isLoading = false,
                isSaving = false,
                descriptionResId = if (isEmpty && !baseState.isLoading) {
                    R.string.builtin_tool_empty
                } else {
                    R.string.builtin_tool_page_description
                },
            )
        )
    }
}
