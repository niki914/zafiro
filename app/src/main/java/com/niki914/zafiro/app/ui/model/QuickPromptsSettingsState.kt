package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.home.composer.suggestion.BuiltinSlashCommand
import com.niki914.zafiro.app.ui.home.composer.suggestion.SlashCategory
import com.niki914.zafiro.app.ui.settings.form.buildFormSpec
import com.niki914.zafiro.repo.RuntimeQuickPrompt
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

data class QuickPromptFormState(
    val editingIndex: Int? = null,
    val name: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: QuickPromptFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val promptErrorResId: Int? = null,
)

data class QuickPromptFormSnapshot(
    val name: String,
    val prompt: String,
    val enabled: Boolean,
)

val QuickPromptFormSpec = buildFormSpec<QuickPromptFormSnapshot, String> {
    field("name", R.string.quick_prompt_field_name) { it.name }
    field("prompt", R.string.quick_prompt_field_prompt) { it.prompt }
    field("enabled", R.string.quick_prompt_field_enabled) { it.enabled }
}

val QuickPromptFormState.hasUnsavedChanges: Boolean
    get() = QuickPromptFormSpec.hasChanges(initialSnapshot, toSnapshot())

fun QuickPromptFormState.unsavedFieldLabelRes(): List<Int> {
    return QuickPromptFormSpec.dirtyFieldLabelRes(initialSnapshot, toSnapshot())
}

data class QuickPromptDeleteConfirmationState(
    val value: String,
)

data class QuickPromptsSettingsUiState(
    val items: List<RuntimeQuickPrompt> = emptyList(),
    val formState: QuickPromptFormState = QuickPromptFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: QuickPromptsInlineError? = null,
    val deleteConfirmation: QuickPromptDeleteConfirmationState? = null,
)

sealed interface QuickPromptsSettingsIntent {
    data object Load : QuickPromptsSettingsIntent
    data object StartCreate : QuickPromptsSettingsIntent
    data class StartEdit(val index: Int) : QuickPromptsSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : QuickPromptsSettingsIntent
    data class NameChanged(val value: String) : QuickPromptsSettingsIntent
    data class PromptChanged(val value: String) : QuickPromptsSettingsIntent
    data class EnabledChanged(val value: Boolean) : QuickPromptsSettingsIntent
    data object Save : QuickPromptsSettingsIntent
    data object RequestDelete : QuickPromptsSettingsIntent
    data object DismissDeleteConfirmation : QuickPromptsSettingsIntent
    data object ConfirmDelete : QuickPromptsSettingsIntent
}

sealed interface QuickPromptsInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        QuickPromptsInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        QuickPromptsInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        QuickPromptsInlineError
}

sealed interface QuickPromptsSettingsEffect {
    data object ExitDetail : QuickPromptsSettingsEffect
    data object FocusName : QuickPromptsSettingsEffect
    data object FocusPrompt : QuickPromptsSettingsEffect
}

class QuickPromptsSettingsViewModel :
    ComposeMVIViewModel<QuickPromptsSettingsIntent, QuickPromptsSettingsUiState, QuickPromptsSettingsEffect>() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): QuickPromptsSettingsUiState = QuickPromptsSettingsUiState()

    override suspend fun handleIntent(intent: QuickPromptsSettingsIntent) {
        when (intent) {
            QuickPromptsSettingsIntent.Load -> load()
            QuickPromptsSettingsIntent.StartCreate -> startCreate()
            is QuickPromptsSettingsIntent.StartEdit -> startEdit(intent.index)
            is QuickPromptsSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is QuickPromptsSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is QuickPromptsSettingsIntent.PromptChanged -> updateState {
                copy(
                    formState = formState.copy(
                        prompt = intent.value,
                        promptErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is QuickPromptsSettingsIntent.EnabledChanged -> updateState {
                copy(formState = formState.copy(enabled = intent.value), inlineError = null)
            }

            QuickPromptsSettingsIntent.Save -> save()
            QuickPromptsSettingsIntent.RequestDelete -> requestDelete()
            QuickPromptsSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            QuickPromptsSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        runCatchingCancellable {
            XRepo.quickPrompts.list()
        }.onSuccess { loadedItems ->
            Logger.d(
                LOG_TAG,
                "load quick prompts=${loadedItems.size} " +
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
                    inlineError = QuickPromptsInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.quick_prompt_error_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = QuickPromptFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val formState = QuickPromptFormState(
            editingIndex = index,
            name = item.name,
            prompt = item.prompt,
            enabled = item.enabled,
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
            XRepo.quickPrompts.setEnabled(index, enabled)
        }.onSuccess {
            Logger.i(LOG_TAG, "toggleItemEnabled prompt=${currentItem.name} enabled=$enabled")
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        }.onFailure { throwable ->
            Logger.w(
                LOG_TAG,
                "toggleItemEnabled failed prompt=${currentItem.name} reason=${throwable.message}"
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

        val hasInvalidChar = trimmedName.any { char ->
            !char.isLetterOrDigit() && char != '-' && char != '_'
        }

        val nameErrorResId = when {
            trimmedName.isBlank() -> R.string.quick_prompt_error_name_required
            hasInvalidChar || trimmedName.none { it.isLetterOrDigit() } ->
                R.string.quick_prompt_error_name_invalid

            trimmedName.lowercase() in RESERVED_TRIGGER_NAMES ->
                R.string.quick_prompt_error_name_reserved

            currentState.items.anyIndexed { index, item ->
                item.name.equals(trimmedName, ignoreCase = true) && index != editingIndex
            } -> R.string.quick_prompt_error_name_duplicate

            else -> null
        }
        val promptErrorResId = when {
            trimmedPrompt.isBlank() -> R.string.quick_prompt_error_prompt_required
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
                nameErrorResId != null -> sendEffect(QuickPromptsSettingsEffect.FocusName)
                promptErrorResId != null -> sendEffect(QuickPromptsSettingsEffect.FocusPrompt)
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
            val nextPrompt = RuntimeQuickPrompt(
                name = trimmedName,
                prompt = trimmedPrompt,
                enabled = formState.enabled,
            )
            val updatedItems = currentState.items.toMutableList().also { mutableItems ->
                if (editingIndex == null || editingIndex !in mutableItems.indices) {
                    mutableItems += nextPrompt
                } else {
                    mutableItems[editingIndex] = nextPrompt
                }
            }
            XRepo.quickPrompts.replace(previousName, nextPrompt)
            updatedItems
        }.onSuccess { updatedItems ->
            Logger.i(LOG_TAG, "save prompt=$trimmedName success")
            updateState {
                copy(
                    items = updatedItems,
                    formState = formState.copy(
                        name = trimmedName,
                        prompt = trimmedPrompt,
                        initialSnapshot = QuickPromptFormSnapshot(
                            name = trimmedName,
                            prompt = trimmedPrompt,
                            enabled = formState.enabled,
                        ),
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(QuickPromptsSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "save prompt=$trimmedName failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = QuickPromptsInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.quick_prompt_error_save_failed,
                    ),
                )
            }
        }
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val currentItem = currentState.items.getOrNull(editingIndex) ?: return
        updateState {
            copy(
                deleteConfirmation = QuickPromptDeleteConfirmationState(
                    value = currentItem.name,
                )
            )
        }
    }

    private suspend fun confirmDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val currentItem = currentState.items.getOrNull(editingIndex) ?: return
        updateState {
            copy(
                deleteConfirmation = null,
                isSaving = true,
                inlineError = null,
            )
        }
        runCatchingCancellable {
            XRepo.quickPrompts.delete(editingIndex)
            currentState.items.filterIndexed { index, _ -> index != editingIndex }
        }.onSuccess { updatedItems ->
            Logger.i(LOG_TAG, "delete prompt=${currentItem.name} success")
            updateState {
                copy(
                    items = updatedItems,
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(QuickPromptsSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(
                LOG_TAG,
                "delete prompt=${currentItem.name} failed reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = QuickPromptsInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.quick_prompt_error_delete_failed,
                    ),
                )
            }
        }
    }

    private fun notifySettingsChanged() {
        settingsChanges.tryEmit(Unit)
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_QuickPromptsSettings"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val RESERVED_TRIGGER_NAMES: Set<String> = (
            BuiltinSlashCommand.ALL.map { it.id.lowercase() } +
            SlashCategory.entries.map { it.prefix.lowercase() }
        ).toSet()
    }
}

fun QuickPromptFormState.toSnapshot(): QuickPromptFormSnapshot =
    QuickPromptFormSnapshot(
        name = name,
        prompt = prompt,
        enabled = enabled,
    )

fun QuickPromptFormState.withCurrentSnapshotAsInitial(): QuickPromptFormState =
    copy(initialSnapshot = toSnapshot())

private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    var index = 0
    for (item in this) {
        if (predicate(index++, item)) return true
    }
    return false
}
