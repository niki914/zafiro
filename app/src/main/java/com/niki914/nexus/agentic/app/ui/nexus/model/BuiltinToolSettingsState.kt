package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolSettingItem
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolSettingsManager
import com.niki914.nexus.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException

data class BuiltinToolSettingsUiState(
    val items: List<BuiltinToolSettingItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    @param:StringRes val descriptionResId: Int = R.string.builtin_tool_loading,
    val descriptionArg: String? = null,
)

sealed interface BuiltinToolSettingsIntent {
    data object Load : BuiltinToolSettingsIntent
    data class ItemEnabledChanged(
        val name: String,
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

    override suspend fun handleIntent(intent: BuiltinToolSettingsIntent) {
        when (intent) {
            BuiltinToolSettingsIntent.Load -> load()
            is BuiltinToolSettingsIntent.ItemEnabledChanged -> setEnabled(
                name = intent.name,
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
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    descriptionResId = loadedItems.descriptionResId(),
                    descriptionArg = null,
                )
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
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
        val previousItems = currentState.items
        val updatedItems = previousItems.map { item ->
            if (item.name == name) {
                item.copy(enabled = enabled)
            } else {
                item
            }
        }
        updateState {
            copy(
                items = updatedItems,
                isSaving = true,
                descriptionResId = updatedItems.descriptionResId(),
                descriptionArg = null,
            )
        }
        runCatching {
            manager.setEnabled(name, enabled)
        }.onSuccess { result ->
            if (result.ok) {
                refreshAfterSave(fallback = updatedItems)
            } else {
                updateState {
                    copy(
                        items = previousItems,
                        isSaving = false,
                        descriptionResId = R.string.builtin_tool_save_failed,
                        descriptionArg = result.message,
                    )
                }
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            updateState {
                copy(
                    items = previousItems,
                    isSaving = false,
                    descriptionResId = R.string.builtin_tool_save_failed,
                    descriptionArg = throwable.message ?: throwable::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun refreshAfterSave(fallback: List<BuiltinToolSettingItem>) {
        runCatching {
            manager.load()
        }.onSuccess { loadedItems ->
            updateState {
                copy(
                    items = loadedItems,
                    isSaving = false,
                    descriptionResId = loadedItems.descriptionResId(),
                    descriptionArg = null,
                )
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            updateState {
                copy(
                    items = fallback,
                    isSaving = false,
                    descriptionResId = fallback.descriptionResId(),
                    descriptionArg = null,
                )
            }
        }
    }
}

private fun List<BuiltinToolSettingItem>.descriptionResId(): Int {
    return if (isEmpty()) {
        R.string.builtin_tool_empty
    } else {
        R.string.builtin_tool_page_description
    }
}
