package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation as CustomToolValidation

data class CustomToolItem(
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean,
)

data class CustomToolFormState(
    val editingIndex: Int? = null,
    val previousName: String? = null,
    val name: String = "",
    val description: String = "",
    val command: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: CustomToolFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val descriptionErrorResId: Int? = null,
    @param:StringRes val commandErrorResId: Int? = null,
    val commandErrorMessage: String? = null,
)

data class CustomToolFormSnapshot(
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean,
)

val CustomToolFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class CustomToolDeleteConfirmationState(
    val value: String,
)

data class CustomToolSettingsUiState(
    val items: List<CustomToolItem> = emptyList(),
    val formState: CustomToolFormState = CustomToolFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: CustomToolInlineError? = null,
    val deleteConfirmation: CustomToolDeleteConfirmationState? = null,
)

sealed interface CustomToolSettingsIntent {
    data object Load : CustomToolSettingsIntent
    data object StartCreate : CustomToolSettingsIntent
    data class StartEdit(val index: Int) : CustomToolSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : CustomToolSettingsIntent
    data class NameChanged(val value: String) : CustomToolSettingsIntent
    data class DescriptionChanged(val value: String) : CustomToolSettingsIntent
    data class CommandChanged(val value: String) : CustomToolSettingsIntent
    data class EnabledChanged(val value: Boolean) : CustomToolSettingsIntent
    data object Save : CustomToolSettingsIntent
    data object RequestDelete : CustomToolSettingsIntent
    data object DismissDeleteConfirmation : CustomToolSettingsIntent
    data object ConfirmDelete : CustomToolSettingsIntent
}

sealed interface CustomToolInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) : CustomToolInlineError
    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) : CustomToolInlineError
    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) : CustomToolInlineError
}

sealed interface CustomToolSettingsEffect {
    data object ExitDetail : CustomToolSettingsEffect
    data object FocusName : CustomToolSettingsEffect
    data object FocusDescription : CustomToolSettingsEffect
    data object FocusCommand : CustomToolSettingsEffect
}

class CustomToolSettingsViewModel :
    ComposeMVIViewModel<
            CustomToolSettingsIntent,
            CustomToolSettingsUiState,
            CustomToolSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): CustomToolSettingsUiState = CustomToolSettingsUiState()

    override suspend fun handleIntent(intent: CustomToolSettingsIntent) {
        when (intent) {
            CustomToolSettingsIntent.Load -> load()
            CustomToolSettingsIntent.StartCreate -> startCreate()
            is CustomToolSettingsIntent.StartEdit -> startEdit(intent.index)
            is CustomToolSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is CustomToolSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomToolSettingsIntent.DescriptionChanged -> updateState {
                copy(
                    formState = formState.copy(
                        description = intent.value,
                        descriptionErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomToolSettingsIntent.CommandChanged -> updateState {
                copy(
                    formState = formState.copy(
                        command = intent.value,
                        commandErrorResId = null,
                        commandErrorMessage = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomToolSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            CustomToolSettingsIntent.Save -> save()
            CustomToolSettingsIntent.RequestDelete -> requestDelete()
            CustomToolSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }
            CustomToolSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val loadedItems = XRepo.customTools.list().map { it.toItem() }
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
                    inlineError = CustomToolInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_tool_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = CustomToolFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val formState = CustomToolFormState(
            editingIndex = index,
            previousName = item.name,
            name = item.name,
            description = item.description,
            command = item.command,
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
            XRepo.customTools.setEnabled(currentItem.name, enabled)
            updateState {
                copy(isSaving = false)
            }
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
                    inlineError = CustomToolInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_tool_save_failed,
                    ),
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val normalizedFormState = formState.copy(
            name = formState.name.trim(),
            description = formState.description.trim(),
            command = formState.command.trim(),
        )
        val requiredErrors = requiredFieldErrors(normalizedFormState)
        if (requiredErrors.hasErrors) {
            updateFormErrors(normalizedFormState, requiredErrors)
            firstInvalidFieldEffect(requiredErrors)?.let { effect ->
                sendEffect(effect)
            }
            return
        }

        updateState {
            copy(
                formState = normalizedFormState.copy(
                    nameErrorResId = null,
                    descriptionErrorResId = null,
                    commandErrorResId = null,
                    commandErrorMessage = null,
                ),
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            val nextTool = CustomTool(
                name = normalizedFormState.name,
                description = normalizedFormState.description,
                command = normalizedFormState.command,
                enabled = normalizedFormState.enabled,
            )
            val validation = XRepo.customTools.replace(
                previousName = normalizedFormState.previousName,
                tool = nextTool,
            )
            if (validation != null) {
                handleValidationError(normalizedFormState, validation)
                return
            }

            val nextItem = nextTool.toItem()
            val updatedItems = buildUpdatedItems(normalizedFormState.editingIndex, nextItem)
            updateState {
                copy(
                    items = updatedItems,
                    formState = normalizedFormState.copy(
                        editingIndex = updatedItems.indexOf(nextItem),
                        previousName = nextTool.name,
                        nameErrorResId = null,
                        descriptionErrorResId = null,
                        commandErrorResId = null,
                        commandErrorMessage = null,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(CustomToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomToolInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_tool_save_failed,
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
                deleteConfirmation = CustomToolDeleteConfirmationState(value = value),
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
        try {
            val updatedItems = currentState.items.filterIndexed { index, _ ->
                index != editingIndex
            }
            XRepo.customTools.delete(currentItem.name)
            updateState {
                copy(
                    items = updatedItems,
                    formState = CustomToolFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(CustomToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomToolInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_tool_delete_failed,
                    ),
                )
            }
        }
    }

    private fun handleValidationError(
        formState: CustomToolFormState,
        validation: CustomToolValidation,
    ) {
        val errors = validationToFieldErrors(validation)
        if (errors.hasErrors) {
            updateFormErrors(formState, errors)
            firstInvalidFieldEffect(errors)?.let { effect ->
                sendEffect(effect)
            }
        } else {
            updateState {
                copy(
                    formState = formState,
                    isSaving = false,
                    inlineError = CustomToolInlineError.SaveFailed(validation.message, fallbackResId = R.string.error_custom_tool_save_failed),
                )
            }
        }
    }

    private fun updateFormErrors(
        formState: CustomToolFormState,
        errors: CustomToolFieldErrors,
    ) {
        updateState {
            copy(
                formState = formState.copy(
                    nameErrorResId = errors.nameErrorResId,
                    descriptionErrorResId = errors.descriptionErrorResId,
                    commandErrorResId = errors.commandErrorResId,
                    commandErrorMessage = errors.commandErrorMessage,
                ),
                isSaving = false,
                inlineError = null,
            )
        }
    }

    private fun buildUpdatedItems(
        editingIndex: Int?,
        nextItem: CustomToolItem,
    ): List<CustomToolItem> {
        return currentState.items.toMutableList().also { mutableItems ->
            if (editingIndex == null || editingIndex !in mutableItems.indices) {
                mutableItems += nextItem
            } else {
                mutableItems[editingIndex] = nextItem
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

private data class CustomToolFieldErrors(
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val descriptionErrorResId: Int? = null,
    @param:StringRes val commandErrorResId: Int? = null,
    val commandErrorMessage: String? = null,
) {
    val hasErrors: Boolean
        get() = nameErrorResId != null ||
                descriptionErrorResId != null ||
                commandErrorResId != null ||
                commandErrorMessage != null
}

private fun CustomToolFormState.toSnapshot(): CustomToolFormSnapshot {
    return CustomToolFormSnapshot(
        name = name.trim(),
        description = description.trim(),
        command = command.trim(),
        enabled = enabled,
    )
}

private fun CustomToolFormState.withCurrentSnapshotAsInitial(): CustomToolFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun requiredFieldErrors(formState: CustomToolFormState): CustomToolFieldErrors {
    return CustomToolFieldErrors(
        nameErrorResId = if (formState.name.isBlank()) {
            R.string.custom_tool_error_name_required
        } else {
            null
        },
        descriptionErrorResId = if (formState.description.isBlank()) {
            R.string.custom_tool_error_description_required
        } else {
            null
        },
        commandErrorResId = if (formState.command.isBlank()) {
            R.string.custom_tool_error_command_required
        } else {
            null
        },
    )
}

private fun validationToFieldErrors(validation: CustomToolValidation): CustomToolFieldErrors {
    return when (validation.field) {
        "name" -> CustomToolFieldErrors(
            nameErrorResId = validation.nameErrorResId(),
        )

        "description" -> CustomToolFieldErrors(
            descriptionErrorResId = R.string.custom_tool_error_description_required,
        )

        "command" -> CustomToolFieldErrors(
            commandErrorResId = validation.commandErrorResId(),
            commandErrorMessage = validation.commandErrorMessage(),
        )

        else -> CustomToolFieldErrors()
    }
}

private fun firstInvalidFieldEffect(
    errors: CustomToolFieldErrors,
): CustomToolSettingsEffect? {
    return when {
        errors.nameErrorResId != null -> CustomToolSettingsEffect.FocusName
        errors.descriptionErrorResId != null -> CustomToolSettingsEffect.FocusDescription
        errors.commandErrorResId != null || errors.commandErrorMessage != null -> CustomToolSettingsEffect.FocusCommand
        else -> null
    }
}

@StringRes
private fun CustomToolValidation.nameErrorResId(): Int {
    return when {
        message.contains("Name must start", ignoreCase = true) ->
            R.string.custom_tool_error_name_invalid

        message.contains("Reserved builtin tool name", ignoreCase = true) ->
            R.string.custom_tool_error_name_reserved

        message.contains("Already exists", ignoreCase = true) ->
            R.string.custom_tool_error_name_duplicate

        message.contains("Required field", ignoreCase = true) ->
            R.string.custom_tool_error_name_required

        else -> R.string.custom_tool_error_name_invalid
    }
}

@StringRes
private fun CustomToolValidation.commandErrorResId(): Int? {
    return when {
        message.contains("execution rule", ignoreCase = true) ->
            null

        message.contains("Unsafe command pattern was rejected", ignoreCase = true) ->
            R.string.custom_tool_error_command_unsafe

        message.contains("Required field", ignoreCase = true) ->
            R.string.custom_tool_error_command_required

        else -> R.string.custom_tool_error_command_unsafe
    }
}

private fun CustomToolValidation.commandErrorMessage(): String? {
    return message.takeIf { it.contains("execution rule", ignoreCase = true) }
}

private fun CustomTool.toItem(): CustomToolItem {
    return CustomToolItem(
        name = name,
        description = description,
        command = command,
        enabled = enabled,
    )
}
