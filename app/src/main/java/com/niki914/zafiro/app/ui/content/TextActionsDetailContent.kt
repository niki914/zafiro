package com.niki914.zafiro.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.TextActionDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.TextActionFormState
import com.niki914.zafiro.app.ui.model.TextActionInlineError
import com.niki914.zafiro.app.ui.model.TextActionSettingsEffect
import com.niki914.zafiro.app.ui.model.TextActionSettingsIntent
import com.niki914.zafiro.app.ui.model.TextActionSettingsUiState
import com.niki914.zafiro.app.ui.model.TextActionSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.TextActionDetailPage

@Composable
fun TextActionsDetailContent(
    page: TextActionDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<TextActionSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<TextActionEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(TextActionSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(TextActionSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        TextActionsDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(TextActionSettingsIntent.NameChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(TextActionSettingsIntent.EnabledChanged(value))
            },
            onPromptChange = { value ->
                viewModel.sendIntent(TextActionSettingsIntent.PromptChanged(value))
            },
            onRequiresScreenshotChange = { value ->
                viewModel.sendIntent(TextActionSettingsIntent.RequiresScreenshotChanged(value))
            },
            onRunInBackgroundChange = { value ->
                viewModel.sendIntent(TextActionSettingsIntent.RunInBackgroundChanged(value))
            },
            onSave = {
                viewModel.sendIntent(TextActionSettingsIntent.Save)
            },
        )

        TextActionDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(TextActionSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(TextActionSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(TextActionSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(TextActionSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.actionIndex in uiState.items.indices) {
            viewModel.sendIntent(TextActionSettingsIntent.StartEdit(page.actionIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                TextActionSettingsEffect.ExitDetail -> onBack()
                TextActionSettingsEffect.FocusName -> requestedFocusField =
                    TextActionEditableField.Name

                TextActionSettingsEffect.FocusPrompt -> requestedFocusField =
                    TextActionEditableField.Prompt
            }
        }
    }
}

@Composable
private fun TextActionsDetailContentBody(
    uiState: TextActionSettingsUiState,
    requestedFocusField: TextActionEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onRequiresScreenshotChange: (Boolean) -> Unit,
    onRunInBackgroundChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.text_action_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.text_action_page_description),
        inlineErrorText = textActionInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TextActionEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.text_action_field_name),
                value = uiState.formState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.text_action_field_name_hint),
                description = textActionFieldErrorText(uiState.formState.nameErrorResId),
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.text_action_field_enabled),
                checked = uiState.formState.enabled,
                enabled = !uiState.isSaving,
                onCheckedChange = onEnabledChange,
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TextActionEditableField.Prompt,
                controller = fieldController,
                title = stringResource(R.string.text_action_field_prompt),
                value = uiState.formState.prompt,
                onValueChange = onPromptChange,
                placeholder = stringResource(R.string.text_action_field_prompt_hint),
                description = textActionFieldErrorText(uiState.formState.promptErrorResId),
                enabled = !uiState.isSaving,
                minLines = 4,
                maxLines = 10,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.text_action_field_screenshot),
                description = stringResource(R.string.text_action_field_screenshot_description),
                checked = uiState.formState.requiresScreenshot,
                enabled = !uiState.isSaving,
                onCheckedChange = onRequiresScreenshotChange,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.text_action_field_background),
                description = stringResource(R.string.text_action_field_background_description),
                checked = uiState.formState.runInBackground,
                enabled = !uiState.isSaving,
                onCheckedChange = onRunInBackgroundChange,
            )
        }
    }
}

private enum class TextActionEditableField {
    Name,
    Prompt,
}

@Composable
private fun textActionFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun textActionInlineErrorText(error: TextActionInlineError?): String? {
    return when (error) {
        null -> null
        is TextActionInlineError.LoadFailed -> error.message ?: stringResource(error.fallbackResId)
        is TextActionInlineError.SaveFailed -> error.message ?: stringResource(error.fallbackResId)
        is TextActionInlineError.DeleteFailed -> error.message
            ?: stringResource(error.fallbackResId)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TextActionsDetailContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TextActionsDetailContentBody(
                uiState = TextActionSettingsUiState(
                    items = emptyList(),
                    formState = TextActionFormState(
                        name = "Translate",
                        prompt = "Translate the following text into English.",
                        enabled = true,
                        requiresScreenshot = false,
                        runInBackground = true,
                        nameErrorResId = R.string.text_action_error_name_duplicate,
                    ),
                    isLoading = false,
                    isSaving = false,
                ),
                requestedFocusField = TextActionEditableField.Name,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledChange = {},
                onPromptChange = {},
                onRequiresScreenshotChange = {},
                onRunInBackgroundChange = {},
                onSave = {},
            )
        }
    }
}

@Composable
private fun TextActionDeleteConfirmationDialog(
    state: TextActionDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.text_action_delete_dialog_title),
        text = stringResource(R.string.text_action_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
