package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.EnvContextInlineError
import com.niki914.zafiro.app.ui.model.EnvContextSettingsEffect
import com.niki914.zafiro.app.ui.model.EnvContextSettingsIntent
import com.niki914.zafiro.app.ui.model.EnvContextSettingsUiState
import com.niki914.zafiro.app.ui.model.EnvContextSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.model.unsavedFieldLabelRes
import com.niki914.zafiro.app.ui.nav.EnvContextVariableDetailPage
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem

enum class EnvContextEditableField {
    Key,
    Value,
}

@Composable
fun EnvContextVariableDetailContent(
    page: EnvContextVariableDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<EnvContextSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<EnvContextEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = { uiState.formState.hasUnsavedChanges },
        unsavedFieldLabelRes = { uiState.formState.unsavedFieldLabelRes() },
        onDiscardChanges = onBack,
        onDelete = {
            viewModel.sendIntent(EnvContextSettingsIntent.RequestDeleteVariable)
        },
        hasDeleteConfirmation = { uiState.deleteConfirmation != null },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(EnvContextSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        EditableSettingsDetailFormScaffold(
            actionText = stringResource(R.string.ui_settings_env_context_save_action),
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = { requestedFocusField = null },
            onActionClick = { viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable) },
            description = stringResource(R.string.ui_settings_env_context_variable_description),
            inlineErrorText = envContextInlineErrorText(uiState.inlineError),
            actionEnabled = !uiState.isSaving,
        ) { fieldController ->
            SettingsGroupCard {
                SettingControlledExpandableTextItem(
                    field = EnvContextEditableField.Key,
                    controller = fieldController,
                    title = stringResource(R.string.ui_settings_env_context_field_name),
                    value = uiState.formState.key,
                    onValueChange = { value ->
                        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged(value))
                    },
                    placeholder = "role",
                    description = uiState.formState.keyErrorResId?.let { stringResource(it) }
                        ?: stringResource(R.string.ui_settings_env_context_field_name_hint),
                    enabled = !uiState.isSaving,
                    minLines = 1,
                    maxLines = 1,
                )
                SettingsItemDivider()
                SettingToggleItem(
                    title = stringResource(R.string.ui_settings_env_context_field_enabled),
                    checked = uiState.formState.enabled,
                    enabled = !uiState.isSaving,
                    onCheckedChange = { checked ->
                        fieldController.clearActiveField()
                        viewModel.sendIntent(EnvContextSettingsIntent.EnabledInputChanged(checked))
                    },
                )
            }

            SettingsGroupCard {
                SettingControlledExpandableTextItem(
                    field = EnvContextEditableField.Value,
                    controller = fieldController,
                    title = stringResource(R.string.ui_settings_env_context_field_content),
                    value = uiState.formState.value,
                    onValueChange = { value ->
                        viewModel.sendIntent(EnvContextSettingsIntent.ValueInputChanged(value))
                    },
                    placeholder = stringResource(R.string.ui_settings_env_context_field_content_hint),
                    description = uiState.formState.valueErrorResId?.let { stringResource(it) },
                    enabled = !uiState.isSaving,
                    minLines = 1,
                    maxLines = 3,
                )
            }
        }

        ZafiroConfirmationDialog(
            visible = uiState.deleteConfirmation != null,
            onDismissRequest = {
                viewModel.sendIntent(EnvContextSettingsIntent.DismissDeleteConfirmation)
            },
            title = stringResource(R.string.ui_settings_env_context_delete_dialog_title),
            text = stringResource(
                R.string.ui_settings_env_context_delete_dialog_text,
                uiState.deleteConfirmation?.key.orEmpty(),
            ),
            negativeButtonText = stringResource(android.R.string.cancel),
            positiveButtonText = stringResource(R.string.delete_dialog_confirm),
            onNegativeClick = {
                viewModel.sendIntent(EnvContextSettingsIntent.DismissDeleteConfirmation)
            },
            onPositiveClick = {
                viewModel.sendIntent(EnvContextSettingsIntent.ConfirmDeleteVariable)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        viewModel.sendIntent(EnvContextSettingsIntent.Load)
        if (page.isCreating) {
            viewModel.sendIntent(EnvContextSettingsIntent.StartCreateVariable)
        } else {
            page.variableKey?.let {
                viewModel.sendIntent(EnvContextSettingsIntent.StartEditVariable(it))
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                EnvContextSettingsEffect.ExitDetail -> onBack()
                EnvContextSettingsEffect.FocusKey -> requestedFocusField = EnvContextEditableField.Key
                EnvContextSettingsEffect.FocusValue -> requestedFocusField = EnvContextEditableField.Value
            }
        }
    }
}

@Composable
private fun envContextInlineErrorText(error: EnvContextInlineError?): String? {
    return when (error) {
        null -> null
        is EnvContextInlineError.SaveFailed -> stringResource(
            R.string.ui_settings_env_context_error_save_failed,
            error.causeMessage.orEmpty(),
        )

        is EnvContextInlineError.DeleteFailed -> stringResource(
            R.string.ui_settings_env_context_error_delete_failed,
            error.causeMessage.orEmpty(),
        )
    }
}
