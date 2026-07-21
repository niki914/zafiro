package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

data class ExecutionRuleItem(
    val id: String,
    val name: String,
    val enabledMode: ExecutionRuleEnabledMode,
    val patterns: List<String>,
)

data class ExecutionRuleFormState(
    val editingIndex: Int? = null,
    val previousId: String? = null,
    val id: String? = null,
    val name: String = "",
    val enabledMode: ExecutionRuleEnabledMode = ExecutionRuleEnabledMode.ALWAYS,
    val patternsInput: String = "",
    val initialSnapshot: ExecutionRuleFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val patternsErrorResId: Int? = null,
)

data class ExecutionRuleFormSnapshot(
    val name: String,
    val enabledMode: ExecutionRuleEnabledMode,
    val patterns: List<String>,
)

val ExecutionRuleFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class ExecutionRuleDeleteConfirmationState(
    val value: String,
)

data class ExecutionRulesSettingsUiState(
    val items: List<ExecutionRuleItem> = emptyList(),
    val formState: ExecutionRuleFormState = ExecutionRuleFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: ExecutionRulesInlineError? = null,
    val deleteConfirmation: ExecutionRuleDeleteConfirmationState? = null,
)

sealed interface ExecutionRulesSettingsIntent {
    data object Load : ExecutionRulesSettingsIntent
    data object StartCreate : ExecutionRulesSettingsIntent
    data class StartEdit(val index: Int) : ExecutionRulesSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : ExecutionRulesSettingsIntent
    data class NameChanged(val value: String) : ExecutionRulesSettingsIntent
    data class EnabledModeChanged(val value: ExecutionRuleEnabledMode) :
        ExecutionRulesSettingsIntent

    data class PatternsChanged(val value: String) : ExecutionRulesSettingsIntent
    data object Save : ExecutionRulesSettingsIntent
    data object RequestDelete : ExecutionRulesSettingsIntent
    data object DismissDeleteConfirmation : ExecutionRulesSettingsIntent
    data object ConfirmDelete : ExecutionRulesSettingsIntent
}

sealed interface ExecutionRulesInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) : ExecutionRulesInlineError
    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) : ExecutionRulesInlineError
    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) : ExecutionRulesInlineError
}

sealed interface ExecutionRulesSettingsEffect {
    data object ExitDetail : ExecutionRulesSettingsEffect
    data object FocusName : ExecutionRulesSettingsEffect
    data object FocusPatterns : ExecutionRulesSettingsEffect
}

class ExecutionRulesSettingsViewModel :
    ComposeMVIViewModel<
            ExecutionRulesSettingsIntent,
            ExecutionRulesSettingsUiState,
            ExecutionRulesSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): ExecutionRulesSettingsUiState = ExecutionRulesSettingsUiState()

    override suspend fun handleIntent(intent: ExecutionRulesSettingsIntent) {
        when (intent) {
            ExecutionRulesSettingsIntent.Load -> load()
            ExecutionRulesSettingsIntent.StartCreate -> startCreate()
            is ExecutionRulesSettingsIntent.StartEdit -> startEdit(intent.index)
            is ExecutionRulesSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is ExecutionRulesSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is ExecutionRulesSettingsIntent.EnabledModeChanged -> updateState {
                copy(
                    formState = formState.copy(enabledMode = intent.value),
                    inlineError = null,
                )
            }

            is ExecutionRulesSettingsIntent.PatternsChanged -> updateState {
                copy(
                    formState = formState.copy(
                        patternsInput = intent.value,
                        patternsErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            ExecutionRulesSettingsIntent.Save -> save()
            ExecutionRulesSettingsIntent.RequestDelete -> requestDelete()
            ExecutionRulesSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }
            ExecutionRulesSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val loadedItems = XRepo.executionRules.list().map { it.toItem() }
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isLoading = false,
                    inlineError = ExecutionRulesInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_exec_rules_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = ExecutionRuleFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val formState = ExecutionRuleFormState(
            editingIndex = index,
            previousId = item.id,
            id = item.id,
            name = item.name,
            enabledMode = item.enabledMode,
            patternsInput = item.patterns.joinToString(separator = "\n"),
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
        val nextMode = if (enabled) {
            ExecutionRuleEnabledMode.ALWAYS
        } else {
            ExecutionRuleEnabledMode.DISABLED
        }
        val previousItems = currentState.items
        val updatedItems = currentState.items.toMutableList().apply {
            this[index] = currentItem.copy(enabledMode = nextMode)
        }
        updateState {
            copy(
                items = updatedItems,
                formState = if (formState.editingIndex == index) {
                    formState.copy(enabledMode = nextMode).withCurrentSnapshotAsInitial()
                } else {
                    formState
                },
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            XRepo.executionRules.setEnabledMode(currentItem.id, nextMode)
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    items = previousItems,
                    formState = if (formState.editingIndex == index) {
                        formState.copy(enabledMode = previousItems[index].enabledMode)
                            .withCurrentSnapshotAsInitial()
                    } else {
                        formState
                    },
                    isSaving = false,
                    inlineError = ExecutionRulesInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_exec_rules_save_failed,
                    ),
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val normalizedName = formState.name.trim()
        val normalizedPatterns = formState.patternsInput.normalizedPatterns()
        if (normalizedName.isBlank() || normalizedPatterns.isEmpty()) {
            val nameErrorResId = if (normalizedName.isBlank()) {
                R.string.execution_rules_error_name_required
            } else {
                null
            }
            val patternsErrorResId = if (normalizedPatterns.isEmpty()) {
                R.string.execution_rules_error_patterns_required
            } else {
                null
            }
            updateState {
                copy(
                    formState = formState.copy(
                        name = normalizedName,
                        patternsInput = normalizedPatterns.joinToString(separator = "\n"),
                        nameErrorResId = nameErrorResId,
                        patternsErrorResId = patternsErrorResId,
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            sendEffect(
                if (nameErrorResId != null) {
                    ExecutionRulesSettingsEffect.FocusName
                } else {
                    ExecutionRulesSettingsEffect.FocusPatterns
                }
            )
            return
        }

        val normalizedFormState = formState.copy(
            name = normalizedName,
            patternsInput = normalizedPatterns.joinToString(separator = "\n"),
        )
        updateState {
            copy(
                isSaving = true,
                formState = normalizedFormState.copy(
                    nameErrorResId = null,
                    patternsErrorResId = null,
                ),
                inlineError = null,
            )
        }
        try {
            val nextRule = ExecutionRule(
                id = normalizedFormState.id ?: newRuleId(),
                name = normalizedFormState.name,
                enabledMode = normalizedFormState.enabledMode,
                patterns = normalizedPatterns,
            )
            XRepo.executionRules.replace(
                previousId = normalizedFormState.previousId,
                rule = nextRule,
            )
            val nextItem = nextRule.toItem()
            val updatedItems = currentState.items.toMutableList().also { mutableItems ->
                val editingIndex = normalizedFormState.editingIndex
                if (editingIndex == null || editingIndex !in mutableItems.indices) {
                    mutableItems += nextItem
                } else {
                    mutableItems[editingIndex] = nextItem
                }
            }
            updateState {
                copy(
                    items = updatedItems,
                    formState = normalizedFormState.copy(
                        editingIndex = updatedItems.indexOf(nextItem),
                        previousId = nextRule.id,
                        id = nextRule.id,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(ExecutionRulesSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = ExecutionRulesInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_exec_rules_save_failed,
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
                deleteConfirmation = ExecutionRuleDeleteConfirmationState(value = value),
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
        val currentId = currentState.formState.id ?: return
        val editingIndex = currentState.formState.editingIndex
        updateState { copy(isSaving = true) }
        try {
            XRepo.executionRules.delete(currentId)
            val updatedItems = if (editingIndex != null) {
                currentState.items.filterIndexed { index, _ -> index != editingIndex }
            } else {
                currentState.items.filterNot { it.id == currentId }
            }
            updateState {
                copy(
                    items = updatedItems,
                    formState = ExecutionRuleFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(ExecutionRulesSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = ExecutionRulesInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_exec_rules_delete_failed,
                    ),
                )
            }
        }
    }

    private fun notifySettingsChanged() {
        settingsChanges.tryEmit(Unit)
    }

    private companion object {
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private fun ExecutionRule.toItem(): ExecutionRuleItem {
    return ExecutionRuleItem(
        id = id,
        name = name,
        enabledMode = enabledMode,
        patterns = patterns,
    )
}

private fun ExecutionRuleFormState.toSnapshot(): ExecutionRuleFormSnapshot {
    return ExecutionRuleFormSnapshot(
        name = name.trim(),
        enabledMode = enabledMode,
        patterns = patternsInput.normalizedPatterns(),
    )
}

private fun ExecutionRuleFormState.withCurrentSnapshotAsInitial(): ExecutionRuleFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun String.normalizedPatterns(): List<String> {
    return lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
}

private fun newRuleId(): String {
    return "custom-${UUID.randomUUID()}"
}
