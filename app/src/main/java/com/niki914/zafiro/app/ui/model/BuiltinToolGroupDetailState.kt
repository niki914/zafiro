package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingItem
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingsManager
import com.niki914.uikit.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException

data class BuiltinToolGroupDetailUiState(
    val groupId: String = "",
    @param:StringRes val titleRes: Int = 0,
    val members: List<BuiltinToolSettingItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
)

sealed interface BuiltinToolGroupDetailIntent {
    data class Load(val groupId: String) : BuiltinToolGroupDetailIntent
    data class ItemEnabledChanged(
        val name: String,
        val value: Boolean,
    ) : BuiltinToolGroupDetailIntent
}

sealed interface BuiltinToolGroupDetailEffect

class BuiltinToolGroupDetailViewModel :
    ComposeMVIViewModel<
            BuiltinToolGroupDetailIntent,
            BuiltinToolGroupDetailUiState,
            BuiltinToolGroupDetailEffect,
            >() {

    private val manager = BuiltinToolSettingsManager()

    override fun initUiState(): BuiltinToolGroupDetailUiState = BuiltinToolGroupDetailUiState()

    private companion object {
        private const val LOG_TAG = "niki914_nexus_BuiltinToolGroupDetailViewModel"
    }

    override suspend fun handleIntent(intent: BuiltinToolGroupDetailIntent) {
        when (intent) {
            is BuiltinToolGroupDetailIntent.Load -> load(intent.groupId)
            is BuiltinToolGroupDetailIntent.ItemEnabledChanged -> setEnabled(
                name = intent.name,
                enabled = intent.value,
            )
        }
    }

    private suspend fun load(groupId: String) {
        val group = runCatching { com.niki914.zafiro.repo.BuiltinToolGroups.find(groupId) }.getOrNull()
        if (group == null) {
            Logger.w(LOG_TAG, "load failed unknown group=$groupId")
            updateState { copy(isLoading = false) }
            return
        }
        updateState { copy(groupId = groupId, titleRes = group.titleRes, isLoading = true) }
        runCatching {
            manager.load()
        }.onSuccess { items ->
            updateState {
                copy(
                    members = group.members.mapNotNull { memberName ->
                        items.firstOrNull { it.name == memberName }
                    },
                    isLoading = false,
                )
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState { copy(isLoading = false) }
        }
    }

    private suspend fun setEnabled(name: String, enabled: Boolean) {
        val previousMembers = currentState.members
        updateState {
            copy(
                members = previousMembers.map { item ->
                    if (item.name == name) item.copy(enabled = enabled) else item
                },
                isSaving = true,
            )
        }
        runCatching {
            manager.setEnabled(name, enabled)
        }.onSuccess { result ->
            updateState { copy(isSaving = false) }
            Logger.i(LOG_TAG, "setEnabled ok=$name success=${result.ok}")
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.w(LOG_TAG, "setEnabled failed tool=$name reason=${throwable.message}")
            updateState { copy(members = previousMembers, isSaving = false) }
        }
    }
}
