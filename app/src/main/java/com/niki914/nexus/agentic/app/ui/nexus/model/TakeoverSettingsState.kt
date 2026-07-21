package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.runtime.settings.model.TAKEOVER_FIELD_NAME
import com.niki914.nexus.agentic.runtime.settings.model.TAKEOVER_FIELD_PATTERNS
import com.niki914.nexus.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class TakeoverTarget {
    NativeAssistant,
    Nexus,
}

data class TakeoverRuleItem(
    val id: String,
    val name: String,
    val target: TakeoverTarget,
    val patterns: List<String>,
    val enabled: Boolean = true,
)

data class TakeoverRuleFormState(
    val editingIndex: Int? = null,
    val previousId: String? = null,
    val id: String? = null,
    val name: String = "",
    val target: TakeoverTarget = TakeoverTarget.NativeAssistant,
    val patternsInput: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: TakeoverRuleFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val patternsErrorResId: Int? = null,
)

data class TakeoverRuleFormSnapshot(
    val name: String,
    val target: TakeoverTarget,
    val enabled: Boolean,
    val patterns: List<String>,
)

val TakeoverRuleFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class TakeoverDeleteConfirmationState(
    val value: String,
)

data class TakeoverSettingsUiState(
    val items: List<TakeoverRuleItem> = emptyList(),
    val defaultTarget: TakeoverTarget = TakeoverTarget.Nexus,
    val formState: TakeoverRuleFormState = TakeoverRuleFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: TakeoverInlineError? = null,
    val deleteConfirmation: TakeoverDeleteConfirmationState? = null,
)

sealed interface TakeoverSettingsIntent {
    data object Load : TakeoverSettingsIntent
    data object StartCreate : TakeoverSettingsIntent
    data class StartEdit(val id: String) : TakeoverSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : TakeoverSettingsIntent
    data class NameChanged(val value: String) : TakeoverSettingsIntent
    data class TargetChanged(val value: TakeoverTarget) : TakeoverSettingsIntent
    data class EnabledChanged(val value: Boolean) : TakeoverSettingsIntent
    data class PatternsChanged(val value: String) : TakeoverSettingsIntent
    data object Save : TakeoverSettingsIntent
    data object RequestDelete : TakeoverSettingsIntent
    data object DismissDeleteConfirmation : TakeoverSettingsIntent
    data object ConfirmDelete : TakeoverSettingsIntent
    data class DefaultTargetChanged(val value: TakeoverTarget) : TakeoverSettingsIntent
}

sealed interface TakeoverInlineError {
    data class LoadFailed(val causeMessage: String? = null) : TakeoverInlineError
    data class SaveFailed(val causeMessage: String? = null) : TakeoverInlineError
    data class DeleteFailed(val causeMessage: String? = null) : TakeoverInlineError
}

sealed interface TakeoverSettingsEffect {
    data object ExitDetail : TakeoverSettingsEffect
    data object FocusName : TakeoverSettingsEffect
    data object FocusPatterns : TakeoverSettingsEffect
}

class TakeoverSettingsViewModel :
    ComposeMVIViewModel<
            TakeoverSettingsIntent,
            TakeoverSettingsUiState,
            TakeoverSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): TakeoverSettingsUiState = TakeoverSettingsUiState()

    override suspend fun handleIntent(intent: TakeoverSettingsIntent) {
        when (intent) {
            TakeoverSettingsIntent.Load -> load()
            TakeoverSettingsIntent.StartCreate -> startCreate()
            is TakeoverSettingsIntent.StartEdit -> startEdit(intent.id)
            is TakeoverSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is TakeoverSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is TakeoverSettingsIntent.TargetChanged -> updateState {
                copy(
                    formState = formState.copy(target = intent.value),
                    inlineError = null,
                )
            }

            is TakeoverSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            is TakeoverSettingsIntent.PatternsChanged -> updateState {
                copy(
                    formState = formState.copy(
                        patternsInput = intent.value,
                        patternsErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            TakeoverSettingsIntent.Save -> save()
            is TakeoverSettingsIntent.DefaultTargetChanged -> setDefaultTarget(intent.value)
            TakeoverSettingsIntent.RequestDelete -> requestDelete()
            TakeoverSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }
            TakeoverSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val loadedItems = XRepo.takeoverRules.list().map { it.toItem() }
            val defaultTarget = XRepo.takeoverRules.getDefaultTarget().toUiTarget()
            updateState {
                copy(
                    items = loadedItems,
                    defaultTarget = defaultTarget,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isLoading = false,
                    inlineError = TakeoverInlineError.LoadFailed(throwable.message),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = TakeoverRuleFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(id: String) {
        val index = currentState.items.indexOfFirst { item -> item.id == id }
        if (index < 0) {
            return
        }
        val item = currentState.items.getOrNull(index) ?: return
        val formState = TakeoverRuleFormState(
            editingIndex = index,
            previousId = item.id,
            id = item.id,
            name = item.name,
            target = item.target,
            patternsInput = item.patterns.joinToString(separator = "\n"),
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
        val updatedItems = currentState.items.toMutableList().apply {
            this[index] = currentItem.copy(enabled = enabled)
        }
        updateState {
            copy(
                items = updatedItems,
                formState = if (formState.editingIndex == index) {
                    formState.copy(enabled = enabled).withCurrentSnapshotAsInitial()
                } else {
                    formState
                },
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            XRepo.takeoverRules.setEnabled(currentItem.id, enabled)
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    items = previousItems,
                    formState = if (formState.editingIndex == index) {
                        formState.copy(enabled = previousItems[index].enabled)
                            .withCurrentSnapshotAsInitial()
                    } else {
                        formState
                    },
                    isSaving = false,
                    inlineError = TakeoverInlineError.SaveFailed(throwable.message),
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val normalizedName = formState.name.trim()
        val normalizedPatterns = formState.patternsInput.normalizedPatterns()
        val normalizedFormState = formState.copy(
            name = normalizedName,
            patternsInput = normalizedPatterns.joinToString(separator = "\n"),
        )
        val nextRule = RuntimeTakeoverRule(
            id = normalizedFormState.id ?: newRuleId(),
            name = normalizedName,
            target = normalizedFormState.target.toRuntime(),
            enabled = normalizedFormState.enabled,
            patterns = normalizedPatterns,
        )
        val validationErrors = XRepo.takeoverRules.validate(nextRule)
        if (validationErrors.isNotEmpty()) {
            val invalidFields = validationErrors.map { error -> error.field }.toSet()
            val nameInvalid = TAKEOVER_FIELD_NAME in invalidFields
            val patternsInvalid = TAKEOVER_FIELD_PATTERNS in invalidFields
            updateState {
                copy(
                    formState = normalizedFormState.copy(
                        nameErrorResId = if (nameInvalid) {
                            R.string.takeover_error_name_required
                        } else {
                            null
                        },
                        patternsErrorResId = if (patternsInvalid) {
                            R.string.takeover_error_patterns_required
                        } else {
                            null
                        },
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            sendEffect(
                if (nameInvalid) {
                    TakeoverSettingsEffect.FocusName
                } else {
                    TakeoverSettingsEffect.FocusPatterns
                }
            )
            return
        }

        updateState {
            copy(
                formState = normalizedFormState,
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            XRepo.takeoverRules.replace(
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
            sendEffect(TakeoverSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TakeoverInlineError.SaveFailed(throwable.message),
                )
            }
        }
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val value = currentState.items.getOrNull(editingIndex)?.name ?: return
        updateState {
            copy(
                deleteConfirmation = TakeoverDeleteConfirmationState(value = value),
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
            XRepo.takeoverRules.delete(currentId)
            val updatedItems = if (editingIndex != null) {
                currentState.items.filterIndexed { index, _ -> index != editingIndex }
            } else {
                currentState.items.filterNot { it.id == currentId }
            }
            updateState {
                copy(
                    items = updatedItems,
                    formState = TakeoverRuleFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(TakeoverSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = TakeoverInlineError.DeleteFailed(throwable.message),
                )
            }
        }
    }

    private suspend fun setDefaultTarget(target: TakeoverTarget) {
        val previousTarget = currentState.defaultTarget
        updateState { copy(defaultTarget = target, inlineError = null, isSaving = true) }
        try {
            XRepo.takeoverRules.setDefaultTarget(target.toRuntime())
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    defaultTarget = previousTarget,
                    isSaving = false,
                    inlineError = TakeoverInlineError.SaveFailed(throwable.message),
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

fun TakeoverTarget.toRuntime(): RuntimeTakeoverTarget {
    return when (this) {
        TakeoverTarget.NativeAssistant -> RuntimeTakeoverTarget.NATIVE_ASSISTANT
        TakeoverTarget.Nexus -> RuntimeTakeoverTarget.NEXUS
    }
}

fun RuntimeTakeoverTarget.toUiTarget(): TakeoverTarget {
    return when (this) {
        RuntimeTakeoverTarget.NATIVE_ASSISTANT -> TakeoverTarget.NativeAssistant
        RuntimeTakeoverTarget.NEXUS -> TakeoverTarget.Nexus
    }
}

private fun RuntimeTakeoverRule.toItem(): TakeoverRuleItem {
    return TakeoverRuleItem(
        id = id,
        name = name,
        target = target.toUiTarget(),
        patterns = patterns,
        enabled = enabled,
    )
}

private fun TakeoverRuleFormState.toSnapshot(): TakeoverRuleFormSnapshot {
    return TakeoverRuleFormSnapshot(
        name = name.trim(),
        target = target,
        enabled = enabled,
        patterns = patternsInput.normalizedPatterns(),
    )
}

private fun TakeoverRuleFormState.withCurrentSnapshotAsInitial(): TakeoverRuleFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun String.normalizedPatterns(): List<String> {
    return lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
}

private fun newRuleId(): String {
    return "takeover-${UUID.randomUUID()}"
}
