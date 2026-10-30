package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.settings.form.buildFormSpec
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeMaskingRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class MaskingRuleItem(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val kind: String = "API_KEY",
    val patterns: List<String>,
)

data class MaskingRuleFormState(
    val editingIndex: Int? = null,
    val previousId: String? = null,
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: String = "",
    val patternsInput: String = "",
    val initialSnapshot: MaskingRuleFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val kindErrorResId: Int? = null,
    @param:StringRes val patternsErrorResId: Int? = null,
)

data class MaskingRuleFormSnapshot(
    val name: String,
    val enabled: Boolean,
    val kind: String,
    val patterns: List<String>,
)

val MaskingRuleFormSpec = buildFormSpec<MaskingRuleFormSnapshot, String> {
    field("name", R.string.masking_rules_field_name) { it.name }
    field("enabled", R.string.masking_rules_field_enabled) { it.enabled }
    field("kind", R.string.masking_rules_field_kind) { it.kind }
    field("patterns", R.string.masking_rules_field_patterns) { it.patterns }
}

val MaskingRuleFormState.hasUnsavedChanges: Boolean
    get() = MaskingRuleFormSpec.hasChanges(initialSnapshot, toSnapshot())

fun MaskingRuleFormState.unsavedFieldLabelRes(): List<Int> {
    return MaskingRuleFormSpec.dirtyFieldLabelRes(initialSnapshot, toSnapshot())
}

data class MaskingRuleDeleteConfirmationState(
    val value: String,
)

data class MaskingRulesSettingsUiState(
    val items: List<MaskingRuleItem> = emptyList(),
    val formState: MaskingRuleFormState = MaskingRuleFormState(),
    val deleteConfirmation: MaskingRuleDeleteConfirmationState? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val inlineError: MaskingRulesInlineError? = null,
)

sealed interface MaskingRulesSettingsIntent {
    data object Load : MaskingRulesSettingsIntent
    data object StartCreate : MaskingRulesSettingsIntent
    data class StartEdit(val id: String) : MaskingRulesSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : MaskingRulesSettingsIntent
    data class NameChanged(val value: String) : MaskingRulesSettingsIntent
    data class EnabledChanged(val value: Boolean) : MaskingRulesSettingsIntent
    data class KindChanged(val value: String) : MaskingRulesSettingsIntent
    data class PatternsChanged(val value: String) : MaskingRulesSettingsIntent
    data object Save : MaskingRulesSettingsIntent
    data object RequestDelete : MaskingRulesSettingsIntent
    data object DismissDeleteConfirmation : MaskingRulesSettingsIntent
    data object ConfirmDelete : MaskingRulesSettingsIntent
}

sealed interface MaskingRulesInlineError {
    data class LoadFailed(val causeMessage: String?) : MaskingRulesInlineError
    data class SaveFailed(val causeMessage: String?) : MaskingRulesInlineError
    data class DeleteFailed(val causeMessage: String?) : MaskingRulesInlineError
}

sealed interface MaskingRulesSettingsEffect {
    data object ExitDetail : MaskingRulesSettingsEffect
    data object FocusName : MaskingRulesSettingsEffect
    data object FocusKind : MaskingRulesSettingsEffect
    data object FocusPatterns : MaskingRulesSettingsEffect
}

class MaskingRulesSettingsViewModel :
    ComposeMVIViewModel<
            MaskingRulesSettingsIntent,
            MaskingRulesSettingsUiState,
            MaskingRulesSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): MaskingRulesSettingsUiState = MaskingRulesSettingsUiState()

    override suspend fun handleIntent(intent: MaskingRulesSettingsIntent) {
        when (intent) {
            MaskingRulesSettingsIntent.Load -> load()
            MaskingRulesSettingsIntent.StartCreate -> startCreate()
            is MaskingRulesSettingsIntent.StartEdit -> startEdit(intent.id)
            is MaskingRulesSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is MaskingRulesSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is MaskingRulesSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            is MaskingRulesSettingsIntent.KindChanged -> updateState {
                copy(
                    formState = formState.copy(
                        kind = intent.value,
                        kindErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is MaskingRulesSettingsIntent.PatternsChanged -> updateState {
                copy(
                    formState = formState.copy(
                        patternsInput = intent.value,
                        patternsErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            MaskingRulesSettingsIntent.Save -> save()
            MaskingRulesSettingsIntent.RequestDelete -> requestDelete()
            MaskingRulesSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            MaskingRulesSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        runCatchingCancellable {
            val loadedItems = XRepo.maskingRules.list().map { it.toItem() }
            Logger.d(
                LOG_TAG,
                "load rules=${loadedItems.size} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
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
                    inlineError = MaskingRulesInlineError.LoadFailed(throwable.message),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = MaskingRuleFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(id: String) {
        val index = currentState.items.indexOfFirst { item -> item.id == id }
        if (index < 0) return
        val item = currentState.items.getOrNull(index) ?: return
        val formState = MaskingRuleFormState(
            editingIndex = index,
            previousId = item.id,
            id = item.id,
            name = item.name,
            enabled = item.enabled,
            kind = item.kind,
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
        runCatchingCancellable {
            XRepo.maskingRules.setEnabled(currentItem.id, enabled)
            Logger.i(LOG_TAG, "toggleItemEnabled ruleId=${currentItem.id} enabled=$enabled")
            updateState { copy(isSaving = false) }
            notifySettingsChanged()
        }.onFailure { throwable ->
            Logger.w(
                LOG_TAG,
                "toggleItemEnabled failed ruleId=${currentItem.id} reason=${throwable.message}"
            )
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
                    inlineError = MaskingRulesInlineError.SaveFailed(throwable.message),
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val normalizedName = formState.name.trim()
        val normalizedKind = formState.kind.trim()
        val normalizedPatterns = formState.patternsInput.normalizedPatterns()

        val normalizedFormState = formState.copy(
            name = normalizedName,
            kind = normalizedKind,
            patternsInput = normalizedPatterns.joinToString(separator = "\n"),
        )

        val invalidName = normalizedName.isBlank()
        val kindErrorResId = when {
            normalizedKind.isBlank() -> R.string.masking_rules_error_kind_required
            !RuntimeMaskingRule.isValidKind(normalizedKind) -> R.string.masking_rules_error_kind_invalid
            else -> null
        }
        val invalidPatterns = normalizedPatterns.isEmpty()

        if (invalidName || kindErrorResId != null || invalidPatterns) {
            updateState {
                copy(
                    formState = normalizedFormState.copy(
                        nameErrorResId = if (invalidName) R.string.masking_rules_error_name_required else null,
                        kindErrorResId = kindErrorResId,
                        patternsErrorResId = if (invalidPatterns) R.string.masking_rules_error_patterns_required else null,
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            sendEffect(
                when {
                    invalidName -> MaskingRulesSettingsEffect.FocusName
                    kindErrorResId != null -> MaskingRulesSettingsEffect.FocusKind
                    else -> MaskingRulesSettingsEffect.FocusPatterns
                }
            )
            return
        }

        val nextRule = RuntimeMaskingRule(
            id = normalizedFormState.id ?: newRuleId(),
            name = normalizedName,
            enabled = normalizedFormState.enabled,
            kind = normalizedKind,
            patterns = normalizedPatterns,
        )

        updateState {
            copy(
                formState = normalizedFormState,
                isSaving = true,
                inlineError = null,
            )
        }
        runCatchingCancellable {
            XRepo.maskingRules.replace(
                previousId = normalizedFormState.previousId,
                rule = nextRule,
            )
            Logger.i(LOG_TAG, "save succeeded ruleId=${nextRule.id}")
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
            sendEffect(MaskingRulesSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = MaskingRulesInlineError.SaveFailed(throwable.message),
                )
            }
        }
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val value = currentState.items.getOrNull(editingIndex)?.name ?: return
        updateState {
            copy(
                deleteConfirmation = MaskingRuleDeleteConfirmationState(value = value),
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
        runCatchingCancellable {
            XRepo.maskingRules.delete(currentId)
            Logger.i(LOG_TAG, "deleteCurrent succeeded ruleId=$currentId")
            val updatedItems = if (editingIndex != null) {
                currentState.items.filterIndexed { index, _ -> index != editingIndex }
            } else {
                currentState.items.filterNot { it.id == currentId }
            }
            updateState {
                copy(
                    items = updatedItems,
                    formState = MaskingRuleFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(MaskingRulesSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(LOG_TAG, "deleteCurrent failed ruleId=$currentId reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = MaskingRulesInlineError.DeleteFailed(throwable.message),
                )
            }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_MaskingRulesSettingsViewModel"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun notifySettingsChanged() {
            settingsChanges.tryEmit(Unit)
        }
    }
}

private fun RuntimeMaskingRule.toItem(): MaskingRuleItem {
    return MaskingRuleItem(
        id = id,
        name = name,
        enabled = enabled,
        kind = kind,
        patterns = patterns,
    )
}

fun MaskingRuleFormState.toSnapshot(): MaskingRuleFormSnapshot {
    return MaskingRuleFormSnapshot(
        name = name.trim(),
        enabled = enabled,
        kind = kind.trim(),
        patterns = patternsInput.normalizedPatterns(),
    )
}

fun MaskingRuleFormState.withCurrentSnapshotAsInitial(): MaskingRuleFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun String.normalizedPatterns(): List<String> {
    return lines()
        .map(String::trim)
        .filter(String::isNotBlank)
}

private fun newRuleId(): String = "masking-" + UUID.randomUUID().toString()
