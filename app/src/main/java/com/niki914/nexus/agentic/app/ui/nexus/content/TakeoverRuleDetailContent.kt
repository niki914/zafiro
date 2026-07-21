package com.niki914.nexus.agentic.app.ui.nexus.content

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
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.SettingToggleItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsItemDivider
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsSegmentedSelector
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverRuleFormState
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverTarget
import com.niki914.nexus.agentic.app.ui.nexus.model.hasUnsavedChanges
import com.niki914.nexus.agentic.app.ui.nexus.nav.TakeoverRuleDetailPage

@Composable
fun TakeoverRuleDetailContent(
    page: TakeoverRuleDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<TakeoverSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<TakeoverEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(TakeoverSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(TakeoverSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        TakeoverRuleDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(TakeoverSettingsIntent.NameChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(TakeoverSettingsIntent.EnabledChanged(value))
            },
            onTargetChange = { value ->
                viewModel.sendIntent(TakeoverSettingsIntent.TargetChanged(value))
            },
            onPatternsInputChange = { value ->
                viewModel.sendIntent(TakeoverSettingsIntent.PatternsChanged(value))
            },
            onSave = {
                viewModel.sendIntent(TakeoverSettingsIntent.Save)
            },
        )

        TakeoverDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(TakeoverSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(TakeoverSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(TakeoverSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(TakeoverSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        val ruleId = page.ruleId
        if (!page.isCreating && ruleId != null) {
            viewModel.sendIntent(TakeoverSettingsIntent.StartEdit(ruleId))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                TakeoverSettingsEffect.ExitDetail -> onBack()
                TakeoverSettingsEffect.FocusName -> requestedFocusField = TakeoverEditableField.Name
                TakeoverSettingsEffect.FocusPatterns -> requestedFocusField =
                    TakeoverEditableField.Patterns
            }
        }
    }
}

enum class TakeoverEditableField {
    Name,
    Patterns,
}

@Composable
private fun TakeoverRuleDetailContentBody(
    uiState: TakeoverSettingsUiState,
    requestedFocusField: TakeoverEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTargetChange: (TakeoverTarget) -> Unit,
    onPatternsInputChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.takeover_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.takeover_editor_description),
        inlineErrorText = takeoverInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TakeoverEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.takeover_field_name),
                value = uiState.formState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.takeover_field_name_hint),
                description = takeoverFieldErrorText(uiState.formState.nameErrorResId),
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.takeover_field_enabled),
                checked = uiState.formState.enabled,
                enabled = !uiState.isSaving,
                onCheckedChange = {
                    fieldController.clearActiveField()
                    onEnabledChange(it)
                },
            )
        }

        SettingsGroupCard {
            SettingsSegmentedSelector(
                title = stringResource(R.string.takeover_field_target),
                options = TakeoverTarget.entries,
                selected = uiState.formState.target,
                label = { target -> target.label() },
                enabled = !uiState.isSaving,
                onSelected = {
                    fieldController.clearActiveField()
                    onTargetChange(it)
                },
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TakeoverEditableField.Patterns,
                controller = fieldController,
                title = stringResource(R.string.takeover_field_patterns),
                value = uiState.formState.patternsInput,
                onValueChange = onPatternsInputChange,
                placeholder = stringResource(R.string.takeover_field_patterns_hint),
                description = uiState.formState.patternsErrorResId?.let { errorResId ->
                    stringResource(errorResId)
                } ?: stringResource(R.string.takeover_field_patterns_description),
                enabled = !uiState.isSaving,
                minLines = 4,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun takeoverFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
internal fun TakeoverTarget.label(): String {
    return when (this) {
        TakeoverTarget.NativeAssistant -> stringResource(R.string.takeover_target_native_assistant)
        TakeoverTarget.Nexus -> stringResource(R.string.takeover_target_nexus)
    }
}

@Composable
private fun takeoverInlineErrorText(error: TakeoverInlineError?): String? {
    return when (error) {
        null -> null
        is TakeoverInlineError.LoadFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_load_failed_generic,
            detailedResId = R.string.takeover_error_load_failed,
        )

        is TakeoverInlineError.SaveFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_save_failed_generic,
            detailedResId = R.string.takeover_error_save_failed,
        )

        is TakeoverInlineError.DeleteFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_delete_failed_generic,
            detailedResId = R.string.takeover_error_delete_failed,
        )
    }
}

@Composable
private fun takeoverErrorText(
    message: String?,
    genericResId: Int,
    detailedResId: Int,
): String {
    return if (message.isNullOrBlank()) {
        stringResource(genericResId)
    } else {
        stringResource(detailedResId, message)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TakeoverRuleDetailContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TakeoverRuleDetailContentBody(
                uiState = TakeoverSettingsUiState(
                    formState = TakeoverRuleFormState(
                        name = "小布生活服务",
                        target = TakeoverTarget.NativeAssistant,
                        patternsInput = "天气\n闹钟\n日程",
                        enabled = true,
                    ),
                    isSaving = false,
                ),
                requestedFocusField = TakeoverEditableField.Patterns,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledChange = {},
                onTargetChange = {},
                onPatternsInputChange = {},
                onSave = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TakeoverRuleDetailRegexContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TakeoverRuleDetailContentBody(
                uiState = TakeoverSettingsUiState(
                    formState = TakeoverRuleFormState(
                        name = "复杂问题交给 Nexus",
                        target = TakeoverTarget.Nexus,
                        patternsInput = ".*崩溃.*\n.*日志.*",
                        enabled = true,
                    ),
                    isSaving = false,
                ),
                requestedFocusField = null,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledChange = {},
                onTargetChange = {},
                onPatternsInputChange = {},
                onSave = {},
            )
        }
    }
}

@Composable
private fun TakeoverDeleteConfirmationDialog(
    state: TakeoverDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.takeover_delete_dialog_title),
        text = stringResource(R.string.takeover_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
