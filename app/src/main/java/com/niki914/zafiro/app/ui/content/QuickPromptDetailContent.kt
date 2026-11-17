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
import com.niki914.uikit.infra.component.SettingsConnectedGroup
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.QuickPromptDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.QuickPromptsInlineError
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsEffect
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsIntent
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsUiState
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.model.unsavedFieldLabelRes
import com.niki914.zafiro.app.ui.nav.QuickPromptDetailPage
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem

@Composable
fun QuickPromptDetailContent(
    page: QuickPromptDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<QuickPromptsSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<QuickPromptEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        unsavedFieldLabelRes = {
            uiState.formState.unsavedFieldLabelRes()
        },
        onDelete = {
            viewModel.sendIntent(QuickPromptsSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(QuickPromptsSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        QuickPromptDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(QuickPromptsSettingsIntent.EnabledChanged(value))
            },
            onPromptChange = { value ->
                viewModel.sendIntent(QuickPromptsSettingsIntent.PromptChanged(value))
            },
            onSave = {
                viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
            },
        )

        QuickPromptDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(QuickPromptsSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(QuickPromptsSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(QuickPromptsSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(QuickPromptsSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.promptIndex in uiState.items.indices) {
            viewModel.sendIntent(QuickPromptsSettingsIntent.StartEdit(page.promptIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                QuickPromptsSettingsEffect.ExitDetail -> onBack()
                QuickPromptsSettingsEffect.FocusName -> requestedFocusField =
                    QuickPromptEditableField.Name

                QuickPromptsSettingsEffect.FocusPrompt -> requestedFocusField =
                    QuickPromptEditableField.Prompt
            }
        }
    }
}

@Composable
private fun QuickPromptDetailContentBody(
    uiState: QuickPromptsSettingsUiState,
    requestedFocusField: QuickPromptEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.quick_prompt_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.quick_prompt_page_description),
        inlineErrorText = quickPromptInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        SettingsConnectedGroup {
            item { shape, isStandalone, _ ->
                SettingControlledExpandableTextItem(
                    field = QuickPromptEditableField.Name,
                    controller = fieldController,
                    title = stringResource(R.string.quick_prompt_field_name),
                    value = uiState.formState.name,
                    onValueChange = onNameChange,
                    placeholder = stringResource(R.string.quick_prompt_field_name_hint),
                    description = quickPromptFieldErrorText(uiState.formState.nameErrorResId)
                        ?: stringResource(R.string.quick_prompt_field_name_description),
                    enabled = !uiState.isSaving,
                    shape = shape,
                    isStandaloneCard = isStandalone,
                    minLines = 1,
                    maxLines = 1,
                )
            }
            item { shape, isStandalone, _ ->
                SettingToggleItem(
                    title = stringResource(R.string.quick_prompt_field_enabled),
                    checked = uiState.formState.enabled,
                    enabled = !uiState.isSaving,
                    shape = shape,
                    isStandaloneCard = isStandalone,
                    onCheckedChange = onEnabledChange,
                )
            }
        }

        SettingsConnectedGroup {
            item { shape, isStandalone, _ ->
                SettingControlledExpandableTextItem(
                    field = QuickPromptEditableField.Prompt,
                    controller = fieldController,
                    title = stringResource(R.string.quick_prompt_field_prompt),
                    value = uiState.formState.prompt,
                    onValueChange = onPromptChange,
                    placeholder = stringResource(R.string.quick_prompt_field_prompt_hint),
                    description = quickPromptFieldErrorText(uiState.formState.promptErrorResId),
                    enabled = !uiState.isSaving,
                    shape = shape,
                    isStandaloneCard = isStandalone,
                    minLines = 4,
                    maxLines = 10,
                )
            }
        }
    }
}

private enum class QuickPromptEditableField {
    Name,
    Prompt,
}

@Composable
private fun quickPromptFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun quickPromptInlineErrorText(error: QuickPromptsInlineError?): String? {
    return when (error) {
        null -> null
        is QuickPromptsInlineError.LoadFailed -> error.message ?: stringResource(error.fallbackResId)
        is QuickPromptsInlineError.SaveFailed -> error.message ?: stringResource(error.fallbackResId)
        is QuickPromptsInlineError.DeleteFailed -> error.message
            ?: stringResource(error.fallbackResId)
    }
}

@Composable
private fun QuickPromptDeleteConfirmationDialog(
    state: QuickPromptDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ZafiroConfirmationDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.quick_prompt_delete_dialog_title),
        text = stringResource(R.string.quick_prompt_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
