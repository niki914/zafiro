package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.model.PyToolDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.PyToolInlineError
import com.niki914.zafiro.app.ui.model.PyToolSettingsEffect
import com.niki914.zafiro.app.ui.model.PyToolSettingsIntent
import com.niki914.zafiro.app.ui.model.PyToolSettingsUiState
import com.niki914.zafiro.app.ui.model.PyToolSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.PyToolDetailPage

@Composable
fun PyToolDetailContent(
    page: PyToolDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<PyToolSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable {
        mutableStateOf<PyToolEditableField?>(null)
    }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(PyToolSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(PyToolSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        PyToolDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(PyToolSettingsIntent.NameChanged(value))
            },
            onCodeChange = { value ->
                viewModel.sendIntent(PyToolSettingsIntent.CodeChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(PyToolSettingsIntent.EnabledChanged(value))
            },
            onSave = {
                viewModel.sendIntent(PyToolSettingsIntent.Save)
            },
        )

        PyToolDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(PyToolSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(PyToolSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(PyToolSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(PyToolSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.toolIndex in uiState.items.indices) {
            viewModel.sendIntent(PyToolSettingsIntent.StartEdit(page.toolIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                PyToolSettingsEffect.ExitDetail -> onBack()
                PyToolSettingsEffect.FocusName -> {
                    requestedFocusField = PyToolEditableField.Name
                }

                PyToolSettingsEffect.FocusCode -> {
                    requestedFocusField = PyToolEditableField.Code
                }
            }
        }
    }
}

@Composable
private fun PyToolDetailContentBody(
    uiState: PyToolSettingsUiState,
    requestedFocusField: PyToolEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.py_tool_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.py_tool_editor_description),
        inlineErrorText = pyToolInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        PyToolIdentitySettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onNameChange = onNameChange,
            onEnabledChange = {
                fieldController.clearActiveField()
                onEnabledChange(it)
            },
        )

        PyToolCodeSettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onCodeChange = onCodeChange,
        )
    }
}

private enum class PyToolEditableField {
    Name,
    Code,
}

@Composable
private fun PyToolIdentitySettingsBlock(
    uiState: PyToolSettingsUiState,
    fieldController: EditableDetailFieldController<PyToolEditableField>,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = PyToolEditableField.Name,
            controller = fieldController,
            title = stringResource(R.string.py_tool_field_name),
            value = uiState.formState.name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.py_tool_field_name_hint),
            description = pyToolFieldErrorText(uiState.formState.nameErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingToggleItem(
            title = stringResource(R.string.py_tool_field_enabled),
            checked = uiState.formState.enabled,
            enabled = !uiState.isSaving,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun PyToolCodeSettingsBlock(
    uiState: PyToolSettingsUiState,
    fieldController: EditableDetailFieldController<PyToolEditableField>,
    onCodeChange: (String) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = PyToolEditableField.Code,
            controller = fieldController,
            title = stringResource(R.string.py_tool_field_code),
            value = uiState.formState.code,
            onValueChange = onCodeChange,
            placeholder = stringResource(R.string.py_tool_field_code_hint),
            description = uiState.formState.codeErrorMessage
                ?: pyToolFieldErrorText(uiState.formState.codeErrorResId),
            enabled = !uiState.isSaving,
            minLines = 6,
            maxLines = 16,
        )
    }
}

@Composable
private fun pyToolFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun pyToolInlineErrorText(error: PyToolInlineError?): String? {
    return when (error) {
        null -> null
        is PyToolInlineError.LoadFailed -> stringResource(
            R.string.py_tool_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is PyToolInlineError.SaveFailed -> stringResource(
            R.string.py_tool_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is PyToolInlineError.DeleteFailed -> stringResource(
            R.string.py_tool_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
}

@Composable
private fun PyToolDeleteConfirmationDialog(
    state: PyToolDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.py_tool_delete_dialog_title),
        text = stringResource(R.string.py_tool_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
