package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import com.niki914.uikit.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.niki914.zafiro.settings.model.RuntimePyTool as PyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation as ToolValidation

data class PyToolItem(
    val name: String,
    val enabled: Boolean,
)

data class PyToolFormState(
    val editingIndex: Int? = null,
    val previousName: String? = null,
    val name: String = "",
    val code: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: PyToolFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
)

data class PyToolFormSnapshot(
    val name: String,
    val code: String,
    val enabled: Boolean,
)

val PyToolFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class PyToolDeleteConfirmationState(
    val value: String,
)

data class PyToolSettingsUiState(
    val items: List<PyToolItem> = emptyList(),
    val formState: PyToolFormState = PyToolFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: PyToolInlineError? = null,
    val deleteConfirmation: PyToolDeleteConfirmationState? = null,
)

sealed interface PyToolSettingsIntent {
    data object Load : PyToolSettingsIntent
    data object StartCreate : PyToolSettingsIntent
    data class StartEdit(val index: Int) : PyToolSettingsIntent
    data class NameChanged(val value: String) : PyToolSettingsIntent
    data class CodeChanged(val value: String) : PyToolSettingsIntent
    data class EnabledChanged(val value: Boolean) : PyToolSettingsIntent
    data object Save : PyToolSettingsIntent
    data object RequestDelete : PyToolSettingsIntent
    data object DismissDeleteConfirmation : PyToolSettingsIntent
    data object ConfirmDelete : PyToolSettingsIntent
}

sealed interface PyToolInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        PyToolInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        PyToolInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        PyToolInlineError
}

sealed interface PyToolSettingsEffect {
    data object ExitDetail : PyToolSettingsEffect
    data object FocusName : PyToolSettingsEffect
    data object FocusCode : PyToolSettingsEffect
}

class PyToolSettingsViewModel :
    ComposeMVIViewModel<
            PyToolSettingsIntent,
            PyToolSettingsUiState,
            PyToolSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): PyToolSettingsUiState = PyToolSettingsUiState()

    override suspend fun handleIntent(intent: PyToolSettingsIntent) {
        when (intent) {
            PyToolSettingsIntent.Load -> load()
            PyToolSettingsIntent.StartCreate -> startCreate()
            is PyToolSettingsIntent.StartEdit -> startEdit(intent.index)
            is PyToolSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is PyToolSettingsIntent.CodeChanged -> updateState {
                copy(
                    formState = formState.copy(
                        code = intent.value,
                        codeErrorResId = null,
                        codeErrorMessage = null,
                    ),
                    inlineError = null,
                )
            }

            is PyToolSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            PyToolSettingsIntent.Save -> save()
            PyToolSettingsIntent.RequestDelete -> requestDelete()
            PyToolSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            PyToolSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        try {
            val loadedItems = XRepo.pyTools.list().map { it.toItem() }
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
                    inlineError = PyToolInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_py_tool_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = PyToolFormState()
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
            XRepo.pyTools.get(item.name)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "startEdit load failed tool=${item.name} reason=${throwable.message}")
            null
        }
        if (tool == null) {
            updateState {
                copy(
                    inlineError = PyToolInlineError.LoadFailed(
                        message = null,
                        fallbackResId = R.string.error_py_tool_load_failed,
                    ),
                )
            }
            return
        }
        val formState = PyToolFormState(
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
            val previousTool = XRepo.pyTools.get(
                normalizedFormState.previousName ?: normalizedFormState.name
            )
            val nextTool = PyTool(
                name = normalizedFormState.name,
                code = normalizedFormState.code,
                enabled = normalizedFormState.enabled,
                timeoutMs = previousTool?.timeoutMs ?: PyTool.DEFAULT_PY_TOOL_TIMEOUT_MS,
            )
            val validation = XRepo.pyTools.saveIntrospected(nextTool)
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
                XRepo.pyTools.delete(normalizedFormState.previousName)
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
            sendEffect(PyToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = PyToolInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_py_tool_save_failed,
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
                deleteConfirmation = PyToolDeleteConfirmationState(value = value),
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
            XRepo.pyTools.delete(currentItem.name)
            Logger.i(LOG_TAG, "deleteCurrent succeeded tool=${currentItem.name}")
            updateState {
                copy(
                    items = updatedItems,
                    formState = PyToolFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(PyToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "deleteCurrent failed tool=${currentItem.name} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = PyToolInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_py_tool_delete_failed,
                    ),
                )
            }
        }
    }

    private fun handleValidationError(
        formState: PyToolFormState,
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
                    inlineError = PyToolInlineError.SaveFailed(
                        validation.message,
                        fallbackResId = R.string.error_py_tool_save_failed
                    ),
                )
            }
        }
    }

    private fun updateFormErrors(
        formState: PyToolFormState,
        errors: PyToolFieldErrors,
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
        nextItem: PyToolItem,
    ): List<PyToolItem> {
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
        private const val LOG_TAG = "niki914_nexus_PyToolSettingsViewModel"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private data class PyToolFieldErrors(
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
) {
    val hasErrors: Boolean
        get() = nameErrorResId != null ||
                codeErrorResId != null ||
                codeErrorMessage != null
}

private fun PyToolFormState.toSnapshot(): PyToolFormSnapshot {
    return PyToolFormSnapshot(
        name = name.trim(),
        code = code.trim(),
        enabled = enabled,
    )
}

private fun PyToolFormState.withCurrentSnapshotAsInitial(): PyToolFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun requiredFieldErrors(formState: PyToolFormState): PyToolFieldErrors {
    return PyToolFieldErrors(
        nameErrorResId = if (formState.name.isBlank()) {
            R.string.py_tool_error_name_required
        } else {
            null
        },
        codeErrorResId = if (formState.code.isBlank()) {
            R.string.py_tool_error_code_required
        } else {
            null
        },
    )
}

private fun validationToFieldErrors(validation: ToolValidation): PyToolFieldErrors {
    return when (validation.field) {
        "name" -> PyToolFieldErrors(
            nameErrorResId = validation.nameErrorResId(),
        )

        "code" -> if (validation.message.contains("Required field", ignoreCase = true)) {
            PyToolFieldErrors(codeErrorResId = R.string.py_tool_error_code_required)
        } else {
            // 反射/安全策略的错误信息直接透传给用户（含行号与原因）
            PyToolFieldErrors(codeErrorMessage = validation.message)
        }

        else -> PyToolFieldErrors()
    }
}

private fun firstInvalidFieldEffect(
    errors: PyToolFieldErrors,
): PyToolSettingsEffect? {
    return when {
        errors.nameErrorResId != null -> PyToolSettingsEffect.FocusName
        errors.codeErrorResId != null || errors.codeErrorMessage != null -> PyToolSettingsEffect.FocusCode
        else -> null
    }
}

@StringRes
private fun ToolValidation.nameErrorResId(): Int {
    return when {
        message.contains("Already exists", ignoreCase = true) ->
            R.string.py_tool_error_name_duplicate

        message.contains("Reserved builtin tool name", ignoreCase = true) ->
            R.string.py_tool_error_name_reserved

        else -> R.string.py_tool_error_name_invalid
    }
}

private fun PyTool.toItem(): PyToolItem {
    return PyToolItem(
        name = name,
        enabled = enabled,
    )
}
