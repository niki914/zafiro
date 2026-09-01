package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool as CustomPyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation as ToolValidation

data class CustomPyToolItem(
    val name: String,
    val enabled: Boolean,
)

data class CustomPyToolFormState(
    val editingIndex: Int? = null,
    val previousName: String? = null,
    val name: String = "",
    val code: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: CustomPyToolFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
)

data class CustomPyToolFormSnapshot(
    val name: String,
    val code: String,
    val enabled: Boolean,
)

val CustomPyToolFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class CustomPyToolDeleteConfirmationState(
    val value: String,
)

data class CustomPyToolSettingsUiState(
    val items: List<CustomPyToolItem> = emptyList(),
    val formState: CustomPyToolFormState = CustomPyToolFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: CustomPyToolInlineError? = null,
    val deleteConfirmation: CustomPyToolDeleteConfirmationState? = null,
)

sealed interface CustomPyToolSettingsIntent {
    data object Load : CustomPyToolSettingsIntent
    data object StartCreate : CustomPyToolSettingsIntent
    data class StartEdit(val index: Int) : CustomPyToolSettingsIntent
    data class NameChanged(val value: String) : CustomPyToolSettingsIntent
    data class CodeChanged(val value: String) : CustomPyToolSettingsIntent
    data class EnabledChanged(val value: Boolean) : CustomPyToolSettingsIntent
    data object Save : CustomPyToolSettingsIntent
    data object RequestDelete : CustomPyToolSettingsIntent
    data object DismissDeleteConfirmation : CustomPyToolSettingsIntent
    data object ConfirmDelete : CustomPyToolSettingsIntent
}

sealed interface CustomPyToolInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError
}

sealed interface CustomPyToolSettingsEffect {
    data object ExitDetail : CustomPyToolSettingsEffect
    data object FocusName : CustomPyToolSettingsEffect
    data object FocusCode : CustomPyToolSettingsEffect
}

class CustomPyToolSettingsViewModel :
    ComposeMVIViewModel<
            CustomPyToolSettingsIntent,
            CustomPyToolSettingsUiState,
            CustomPyToolSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): CustomPyToolSettingsUiState = CustomPyToolSettingsUiState()

    override suspend fun handleIntent(intent: CustomPyToolSettingsIntent) {
        when (intent) {
            CustomPyToolSettingsIntent.Load -> load()
            CustomPyToolSettingsIntent.StartCreate -> startCreate()
            is CustomPyToolSettingsIntent.StartEdit -> startEdit(intent.index)
            is CustomPyToolSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomPyToolSettingsIntent.CodeChanged -> updateState {
                copy(
                    formState = formState.copy(
                        code = intent.value,
                        codeErrorResId = null,
                        codeErrorMessage = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomPyToolSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            CustomPyToolSettingsIntent.Save -> save()
            CustomPyToolSettingsIntent.RequestDelete -> requestDelete()
            CustomPyToolSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            CustomPyToolSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        try {
            val loadedItems = XRepo.customPyTools.list().map { it.toItem() }
            Logger.d(
                LOG_TAG,
                "load tools=${loadedItems.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = CustomPyToolInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = CustomPyToolFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private suspend fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val tool = try {
            XRepo.customPyTools.get(item.name)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "startEdit load failed tool=${item.name} reason=${throwable.message}")
            null
        }
        if (tool == null) {
            updateState {
                copy(
                    inlineError = CustomPyToolInlineError.LoadFailed(
                        message = null,
                        fallbackResId = R.string.error_custom_py_tool_load_failed,
                    ),
                )
            }
            return
        }
        val formState = CustomPyToolFormState(
            editingIndex = index,
            previousName = tool.name,
            name = tool.name,
            code = tool.code,
            enabled = tool.enabled,
        )
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val normalizedFormState = formState.copy(
            name = formState.name.trim(),
            code = formState.code.trim(),
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
                    codeErrorResId = null,
                    codeErrorMessage = null,
                ),
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            // timeoutMs 不在 UI 编辑：编辑时保留原值，新建用默认值
            val previousTool = XRepo.customPyTools.get(
                normalizedFormState.previousName ?: normalizedFormState.name
            )
            val nextTool = CustomPyTool(
                name = normalizedFormState.name,
                code = normalizedFormState.code,
                enabled = normalizedFormState.enabled,
                timeoutMs = previousTool?.timeoutMs
                    ?: CustomPyTool.DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS,
            )
            val validation = XRepo.customPyTools.saveIntrospected(nextTool)
            if (validation != null) {
                Logger.w(
                    LOG_TAG,
                    "save rejected tool=${nextTool.name} " +
                            "validation=${validation.field}:${validation.message}"
                )
                handleValidationError(normalizedFormState, validation)
                return
            }
            if (normalizedFormState.previousName != null &&
                normalizedFormState.previousName != nextTool.name
            ) {
                XRepo.customPyTools.delete(normalizedFormState.previousName)
            }
            Logger.i(LOG_TAG, "save succeeded tool=${nextTool.name}")

            val nextItem = nextTool.toItem()
            val updatedItems = buildUpdatedItems(normalizedFormState.editingIndex, nextItem)
            updateState {
                copy(
                    items = updatedItems,
                    formState = normalizedFormState.copy(
                        editingIndex = updatedItems.indexOf(nextItem),
                        previousName = nextTool.name,
                        nameErrorResId = null,
                        codeErrorResId = null,
                        codeErrorMessage = null,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(CustomPyToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomPyToolInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_save_failed,
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
                deleteConfirmation = CustomPyToolDeleteConfirmationState(value = value),
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
            XRepo.customPyTools.delete(currentItem.name)
            Logger.i(LOG_TAG, "deleteCurrent succeeded tool=${currentItem.name}")
            updateState {
                copy(
                    items = updatedItems,
                    formState = CustomPyToolFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(CustomPyToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "deleteCurrent failed tool=${currentItem.name} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomPyToolInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_delete_failed,
                    ),
                )
            }
        }
    }

    private fun handleValidationError(
        formState: CustomPyToolFormState,
        validation: ToolValidation,
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
                    inlineError = CustomPyToolInlineError.SaveFailed(
                        validation.message,
                        fallbackResId = R.string.error_custom_py_tool_save_failed
                    ),
                )
            }
        }
    }

    private fun updateFormErrors(
        formState: CustomPyToolFormState,
        errors: CustomPyToolFieldErrors,
    ) {
        updateState {
            copy(
                formState = formState.copy(
                    nameErrorResId = errors.nameErrorResId,
                    codeErrorResId = errors.codeErrorResId,
                    codeErrorMessage = errors.codeErrorMessage,
                ),
                isSaving = false,
                inlineError = null,
            )
        }
    }

    private fun buildUpdatedItems(
        editingIndex: Int?,
        nextItem: CustomPyToolItem,
    ): List<CustomPyToolItem> {
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
        private const val LOG_TAG = "niki914_nexus_CustomPyToolSettingsViewModel"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private data class CustomPyToolFieldErrors(
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
) {
    val hasErrors: Boolean
        get() = nameErrorResId != null ||
                codeErrorResId != null ||
                codeErrorMessage != null
}

private fun CustomPyToolFormState.toSnapshot(): CustomPyToolFormSnapshot {
    return CustomPyToolFormSnapshot(
        name = name.trim(),
        code = code.trim(),
        enabled = enabled,
    )
}

private fun CustomPyToolFormState.withCurrentSnapshotAsInitial(): CustomPyToolFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun requiredFieldErrors(formState: CustomPyToolFormState): CustomPyToolFieldErrors {
    return CustomPyToolFieldErrors(
        nameErrorResId = if (formState.name.isBlank()) {
            R.string.custom_py_tool_error_name_required
        } else {
            null
        },
        codeErrorResId = if (formState.code.isBlank()) {
            R.string.custom_py_tool_error_code_required
        } else {
            null
        },
    )
}

private fun validationToFieldErrors(validation: ToolValidation): CustomPyToolFieldErrors {
    return when (validation.field) {
        "name" -> CustomPyToolFieldErrors(
            nameErrorResId = validation.nameErrorResId(),
        )

        "code" -> if (validation.message.contains("Required field", ignoreCase = true)) {
            CustomPyToolFieldErrors(codeErrorResId = R.string.custom_py_tool_error_code_required)
        } else {
            // 反射/安全策略的错误信息直接透传给用户（含行号与原因）
            CustomPyToolFieldErrors(codeErrorMessage = validation.message)
        }

        else -> CustomPyToolFieldErrors()
    }
}

private fun firstInvalidFieldEffect(
    errors: CustomPyToolFieldErrors,
): CustomPyToolSettingsEffect? {
    return when {
        errors.nameErrorResId != null -> CustomPyToolSettingsEffect.FocusName
        errors.codeErrorResId != null || errors.codeErrorMessage != null -> CustomPyToolSettingsEffect.FocusCode
        else -> null
    }
}

@StringRes
private fun ToolValidation.nameErrorResId(): Int {
    return when {
        message.contains("Already exists", ignoreCase = true) ->
            R.string.custom_py_tool_error_name_duplicate

        message.contains("Reserved builtin tool name", ignoreCase = true) ->
            R.string.custom_py_tool_error_name_reserved

        else -> R.string.custom_py_tool_error_name_invalid
    }
}

private fun CustomPyTool.toItem(): CustomPyToolItem {
    return CustomPyToolItem(
        name = name,
        enabled = enabled,
    )
}
