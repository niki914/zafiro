package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.RuntimeTextAction
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

data class TextActionFormState(
    val editingIndex: Int? = null,
    val name: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    val requiresScreenshot: Boolean = false,
    val runInBackground: Boolean = false,
    val initialSnapshot: TextActionFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val promptErrorResId: Int? = null,
)

data class TextActionFormSnapshot(
    val name: String,
    val prompt: String,
    val enabled: Boolean,
    val requiresScreenshot: Boolean,
    val runInBackground: Boolean,
)

val TextActionFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class TextActionDeleteConfirmationState(
    val value: String,
)

data class TextActionSettingsUiState(
    val items: List<RuntimeTextAction> = emptyList(),
    val formState: TextActionFormState = TextActionFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: TextActionInlineError? = null,
    val deleteConfirmation: TextActionDeleteConfirmationState? = null,
)

sealed interface TextActionSettingsIntent {
    data object Load : TextActionSettingsIntent
    data object StartCreate : TextActionSettingsIntent
    data class StartEdit(val index: Int) : TextActionSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : TextActionSettingsIntent
    data class NameChanged(val value: String) : TextActionSettingsIntent
    data class PromptChanged(val value: String) : TextActionSettingsIntent
    data class EnabledChanged(val value: Boolean) : TextActionSettingsIntent
    data class RequiresScreenshotChanged(val value: Boolean) : TextActionSettingsIntent
    data class RunInBackgroundChanged(val value: Boolean) : TextActionSettingsIntent
    data object Save : TextActionSettingsIntent
    data object RequestDelete : TextActionSettingsIntent
    data object DismissDeleteConfirmation : TextActionSettingsIntent
    data object ConfirmDelete : TextActionSettingsIntent
}

sealed interface TextActionInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TextActionInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TextActionInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        TextActionInlineError
}

sealed interface TextActionSettingsEffect {
    data object ExitDetail : TextActionSettingsEffect
    data object FocusName : TextActionSettingsEffect
    data object FocusPrompt : TextActionSettingsEffect
}

class TextActionSettingsViewModel :
    ComposeMVIViewModel<TextActionSettingsIntent, TextActionSettingsUiState, TextActionSettingsEffect>() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): TextActionSettingsUiState = TextActionSettingsUiState()

    override suspend fun handleIntent(intent: TextActionSettingsIntent) {
        when (intent) {
            TextActionSettingsIntent.Load -> load()
            TextActionSettingsIntent.StartCreate -> startCreate()
            is TextActionSettingsIntent.StartEdit -> startEdit(intent.index)
            is TextActionSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is TextActionSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is TextActionSettingsIntent.PromptChanged -> updateState {
                copy(
                    formState = formState.copy(
                        prompt = intent.value,
                        promptErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is TextActionSettingsIntent.EnabledChanged -> updateState {
                copy(formState = formState.copy(enabled = intent.value), inlineError = null)
            }

            is TextActionSettingsIntent.RequiresScreenshotChanged -> updateState {
                copy(
                    formState = formState.copy(requiresScreenshot = intent.value),
                    inlineError = null,
                )
            }

            is TextActionSettingsIntent.RunInBackgroundChanged -> updateState {
                copy(
                    formState = formState.copy(runInBackground = intent.value),
                    inlineError = null,
                )
            }

            TextActionSettingsIntent.Save -> save()
            TextActionSettingsIntent.RequestDelete -> requestDelete()
            TextActionSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            TextActionSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        runCatchingCancellable {
            XRepo.textActions.list()
        }.onSuccess { loadedItems ->
            Logger.d(
                LOG_TAG,
                "load actions=${loadedItems.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = TextActionInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.text_action_error_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = TextActionFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val formState = TextActionFormState(
            editingIndex = index,
            name = item.name,
            prompt = item.prompt,
            enabled = item.enabled,
            requiresScreenshot = item.requiresScreenshot,
            runInBackground = item.runInBackground,
        )
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private suspend fun toggleItemEnabled(index: Int, enabled: Boolean) {
        val currentItem = currentState.items.getOrNull(index) ?: return
        val previousItems = currentState.items
        val updatedItems = previousItems.toMutableList().apply {
            this[index] = currentItem.copy(enabled = enabled)
        }
        updateState {
            copy(
                items = updatedItems,
                isSaving = true,
                inlineError = null,
            )
        }
        runCatchingCancellable {
            XRepo.textActions.setEnabled(index, enabled)
        }.onSuccess {
            Logger.i(LOG_TAG, "toggleItemEnabled action=${currentItem.name} enabled=$enabled")
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        }.onFailure { throwable ->
            Logger.w(
                LOG_TAG,
                "toggleItemEnabled failed action=${currentItem.name} reason=${throwable.message}"
            )
            updateState {
                copy(
                    items = previousItems,
                    isSaving = false,
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val trimmedName = formState.name.trim()
        val trimmedPrompt = formState.prompt.trim()
        val editingIndex = formState.editingIndex
        val nameErrorResId = when {
            trimmedName.isBlank() -> R.string.text_action_error_name_required
            currentState.items.anyIndexed { index, item ->
                item.name == trimmedName && index != editingIndex
            } -> R.string.text_action_error_name_duplicate

            else -> null
        }
        val promptErrorResId = when {
            trimmedPrompt.isBlank() -> R.string.text_action_error_prompt_required
            else -> null
        }
        if (nameErrorResId != null || promptErrorResId != null) {
            Logger.w(
                LOG_TAG,
                "save rejected nameError=${nameErrorResId != null} " +
                        "promptError=${promptErrorResId != null}"
            )
            updateState {
                copy(
                    formState = formState.copy(
                        name = trimmedName,
                        prompt = trimmedPrompt,
                        nameErrorResId = nameErrorResId,
                        promptErrorResId = promptErrorResId,
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            when {
                nameErrorResId != null -> sendEffect(TextActionSettingsEffect.FocusName)
                promptErrorResId != null -> sendEffect(TextActionSettingsEffect.FocusPrompt)
            }
            return
        }

        updateState {
            copy(
                formState = formState.copy(
                    name = trimmedName,
                    prompt = trimmedPrompt,
                    nameErrorResId = null,
                    promptErrorResId = null,
                ),
                isSaving = true,
                inlineError = null,
            )
        }
        runCatchingCancellable {
            val previousName = editingIndex?.let { currentState.items.getOrNull(it)?.name }
            val nextAction = RuntimeTextAction(
                name = trimmedName,
                prompt = trimmedPrompt,
                enabled = formState.enabled,
                requiresScreenshot = formState.requiresScreenshot,
                runInBackground = formState.runInBackground,
            )
            val updatedItems = currentState.items.toMutableList().also { mutableItems ->
                if (editingIndex == null || editingIndex !in mutableItems.indices) {
                    mutableItems += nextAction
                } else {
                    mutableItems[editingIndex] = nextAction
                }
            }
            XRepo.textActions.replace(previousName, nextAction)
            Logger.i(LOG_TAG, "save succeeded action=${nextAction.name}")
            updateState {
                copy(
                    items = updatedItems,
                    formState = formState.copy(
                        editingIndex = updatedItems.indexOf(nextAction),
                        name = trimmedName,
                        prompt = trimmedPrompt,
                        nameErrorResId = null,
                        promptErrorResId = null,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(TextActionSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TextActionInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.text_action_error_save_failed,
                    ),
                )
            }
        }
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val value = currentState.items.getOrNull(editingIndex)?.name ?: return
        updateState {
            copy(
                deleteConfirmation = TextActionDeleteConfirmationState(value = value),
                inlineError = null,
            )
        }
    }

    private suspend fun confirmDelete() {
        val confirmation = currentState.deleteConfirmation ?: return
        updateState { copy(deleteConfirmation = null) }
        deleteCurrent()
    }

    private suspend fun deleteCurrent() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val currentItem = currentState.items.getOrNull(editingIndex) ?: return
        updateState { copy(isSaving = true) }
        runCatchingCancellable {
            val updatedItems =
                currentState.items.filterIndexed { index, _ -> index != editingIndex }
            XRepo.textActions.delete(editingIndex)
            Logger.i(LOG_TAG, "deleteCurrent succeeded action=${currentItem.name}")
            updateState {
                copy(
                    items = updatedItems,
                    formState = TextActionFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(TextActionSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(
                LOG_TAG,
                "deleteCurrent failed action=${currentItem.name} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TextActionInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.text_action_error_delete_failed,
                    ),
                )
            }
        }
    }

    private fun notifySettingsChanged() {
        settingsChanges.tryEmit(Unit)
    }

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_TextActionSettingsViewModel"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private fun TextActionFormState.toSnapshot(): TextActionFormSnapshot {
    return TextActionFormSnapshot(
        name = name.trim(),
        prompt = prompt.trim(),
        enabled = enabled,
        requiresScreenshot = requiresScreenshot,
        runInBackground = runInBackground,
    )
}

private fun TextActionFormState.withCurrentSnapshotAsInitial(): TextActionFormState {
    return copy(initialSnapshot = toSnapshot())
}

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, item ->
        if (predicate(index, item)) {
            return true
        }
    }
    return false
}
