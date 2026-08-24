package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingItem
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingsManager
import com.niki914.uikit.base.ComposeMVIViewModel
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
                Logger.i(LOG_TAG, "setEnabled succeeded tool=$name enabled=$enabled")
                refreshAfterSave(fallback = updatedItems)
            } else {
                Logger.w(
                    LOG_TAG,
                    "setEnabled rejected tool=$name enabled=$enabled reason=${result.message}"
                )
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
            Logger.w(LOG_TAG, "setEnabled failed tool=$name reason=${throwable.message}")
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
