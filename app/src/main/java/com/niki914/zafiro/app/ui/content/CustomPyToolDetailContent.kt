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
import com.niki914.zafiro.app.ui.model.CustomPyToolDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.CustomPyToolInlineError
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsEffect
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsIntent
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsUiState
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.CustomPyToolDetailPage

@Composable
fun CustomPyToolDetailContent(
    page: CustomPyToolDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<CustomPyToolSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable {
        mutableStateOf<CustomPyToolEditableField?>(null)
    }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(CustomPyToolSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(CustomPyToolSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        CustomPyToolDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.NameChanged(value))
            },
            onCodeChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.CodeChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.EnabledChanged(value))
            },
            onSave = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.Save)
            },
        )

        CustomPyToolDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(CustomPyToolSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(CustomPyToolSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.toolIndex in uiState.items.indices) {
            viewModel.sendIntent(CustomPyToolSettingsIntent.StartEdit(page.toolIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                CustomPyToolSettingsEffect.ExitDetail -> onBack()
                CustomPyToolSettingsEffect.FocusName -> {
                    requestedFocusField = CustomPyToolEditableField.Name
                }

                CustomPyToolSettingsEffect.FocusCode -> {
                    requestedFocusField = CustomPyToolEditableField.Code
                }
            }
        }
    }
}

@Composable
private fun CustomPyToolDetailContentBody(
    uiState: CustomPyToolSettingsUiState,
    requestedFocusField: CustomPyToolEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.custom_py_tool_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.custom_py_tool_editor_description),
        inlineErrorText = customPyToolInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        CustomPyToolIdentitySettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onNameChange = onNameChange,
            onEnabledChange = {
                fieldController.clearActiveField()
                onEnabledChange(it)
            },
        )

        CustomPyToolCodeSettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onCodeChange = onCodeChange,
        )
    }
}

private enum class CustomPyToolEditableField {
    Name,
    Code,
}

@Composable
private fun CustomPyToolIdentitySettingsBlock(
    uiState: CustomPyToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomPyToolEditableField>,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomPyToolEditableField.Name,
            controller = fieldController,
            title = stringResource(R.string.custom_py_tool_field_name),
            value = uiState.formState.name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.custom_py_tool_field_name_hint),
            description = customPyToolFieldErrorText(uiState.formState.nameErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingToggleItem(
            title = stringResource(R.string.custom_py_tool_field_enabled),
            checked = uiState.formState.enabled,
            enabled = !uiState.isSaving,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun CustomPyToolCodeSettingsBlock(
    uiState: CustomPyToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomPyToolEditableField>,
    onCodeChange: (String) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomPyToolEditableField.Code,
            controller = fieldController,
            title = stringResource(R.string.custom_py_tool_field_code),
            value = uiState.formState.code,
            onValueChange = onCodeChange,
            placeholder = stringResource(R.string.custom_py_tool_field_code_hint),
            description = uiState.formState.codeErrorMessage
                ?: customPyToolFieldErrorText(uiState.formState.codeErrorResId),
            enabled = !uiState.isSaving,
            minLines = 6,
            maxLines = 16,
        )
    }
}

@Composable
private fun customPyToolFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun customPyToolInlineErrorText(error: CustomPyToolInlineError?): String? {
    return when (error) {
        null -> null
        is CustomPyToolInlineError.LoadFailed -> stringResource(
            R.string.custom_py_tool_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomPyToolInlineError.SaveFailed -> stringResource(
            R.string.custom_py_tool_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomPyToolInlineError.DeleteFailed -> stringResource(
            R.string.custom_py_tool_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
}

@Composable
private fun CustomPyToolDeleteConfirmationDialog(
    state: CustomPyToolDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.custom_py_tool_delete_dialog_title),
        text = stringResource(R.string.custom_py_tool_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
