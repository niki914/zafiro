package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.component.SettingToggleItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsItemDivider
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.CustomToolSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.hasUnsavedChanges
import com.niki914.nexus.agentic.app.ui.nexus.nav.CustomToolDetailPage

@Composable
fun CustomToolDetailContent(
    page: CustomToolDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<CustomToolSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable {
        mutableStateOf<CustomToolEditableField?>(null)
    }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(CustomToolSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(CustomToolSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        CustomToolDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(CustomToolSettingsIntent.NameChanged(value))
            },
            onDescriptionChange = { value ->
                viewModel.sendIntent(CustomToolSettingsIntent.DescriptionChanged(value))
            },
            onCommandChange = { value ->
                viewModel.sendIntent(CustomToolSettingsIntent.CommandChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(CustomToolSettingsIntent.EnabledChanged(value))
            },
            onSave = {
                viewModel.sendIntent(CustomToolSettingsIntent.Save)
            },
        )

        CustomToolDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(CustomToolSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(CustomToolSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(CustomToolSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(CustomToolSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.toolIndex in uiState.items.indices) {
            viewModel.sendIntent(CustomToolSettingsIntent.StartEdit(page.toolIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                CustomToolSettingsEffect.ExitDetail -> onBack()
                CustomToolSettingsEffect.FocusName -> {
                    requestedFocusField = CustomToolEditableField.Name
                }

                CustomToolSettingsEffect.FocusDescription -> {
                    requestedFocusField = CustomToolEditableField.Description
                }

                CustomToolSettingsEffect.FocusCommand -> {
                    requestedFocusField = CustomToolEditableField.Command
                }
            }
        }
    }
}

@Composable
private fun CustomToolDetailContentBody(
    uiState: CustomToolSettingsUiState,
    requestedFocusField: CustomToolEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.custom_tool_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.custom_tool_editor_description),
        inlineErrorText = customToolInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        CustomToolIdentitySettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onNameChange = onNameChange,
            onDescriptionChange = onDescriptionChange,
            onEnabledChange = {
                fieldController.clearActiveField()
                onEnabledChange(it)
            },
        )

        CustomToolCommandSettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onCommandChange = onCommandChange,
        )
    }
}

private enum class CustomToolEditableField {
    Name,
    Description,
    Command,
}

@Composable
private fun CustomToolIdentitySettingsBlock(
    uiState: CustomToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomToolEditableField>,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomToolEditableField.Name,
            controller = fieldController,
            title = stringResource(R.string.custom_tool_field_name),
            value = uiState.formState.name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.custom_tool_field_name_hint),
            description = customToolFieldErrorText(uiState.formState.nameErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingControlledExpandableTextItem(
            field = CustomToolEditableField.Description,
            controller = fieldController,
            title = stringResource(R.string.custom_tool_field_description),
            value = uiState.formState.description,
            onValueChange = onDescriptionChange,
            placeholder = stringResource(R.string.custom_tool_field_description_hint),
            description = customToolFieldErrorText(uiState.formState.descriptionErrorResId),
            enabled = !uiState.isSaving,
            minLines = 3,
            maxLines = 8,
        )
        SettingsItemDivider()
        SettingToggleItem(
            title = stringResource(R.string.custom_tool_field_enabled),
            checked = uiState.formState.enabled,
            enabled = !uiState.isSaving,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun CustomToolCommandSettingsBlock(
    uiState: CustomToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomToolEditableField>,
    onCommandChange: (String) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomToolEditableField.Command,
            controller = fieldController,
            title = stringResource(R.string.custom_tool_field_command),
            value = uiState.formState.command,
            onValueChange = onCommandChange,
            placeholder = stringResource(R.string.custom_tool_field_command_hint),
            description = uiState.formState.commandErrorMessage
                ?: customToolFieldErrorText(uiState.formState.commandErrorResId),
            enabled = !uiState.isSaving,
            minLines = 3,
            maxLines = 10,
        )
    }
}

@Composable
private fun customToolFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun customToolInlineErrorText(error: CustomToolInlineError?): String? {
    return when (error) {
        null -> null
        is CustomToolInlineError.LoadFailed -> stringResource(
            R.string.custom_tool_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomToolInlineError.SaveFailed -> stringResource(
            R.string.custom_tool_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomToolInlineError.DeleteFailed -> stringResource(
            R.string.custom_tool_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
}

@Composable
private fun CustomToolDeleteConfirmationDialog(
    state: CustomToolDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.custom_tool_delete_dialog_title),
        text = stringResource(R.string.custom_tool_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
