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
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.screen.ProvideZafiroScreenContentForPreview
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.MaskingRuleDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.MaskingRuleFormState
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsEffect
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsIntent
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsUiState
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.model.unsavedFieldLabelRes
import com.niki914.zafiro.app.ui.nav.MaskingRuleDetailPage
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem

enum class MaskingEditableField {
    Name,
    Kind,
    Patterns,
}

@Composable
fun MaskingRuleDetailContent(
    page: MaskingRuleDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<MaskingRulesSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<MaskingEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        unsavedFieldLabelRes = {
            uiState.formState.unsavedFieldLabelRes()
        },
        onDelete = {
            viewModel.sendIntent(MaskingRulesSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(MaskingRulesSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        MaskingRuleDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(MaskingRulesSettingsIntent.NameChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(MaskingRulesSettingsIntent.EnabledChanged(value))
            },
            onKindChange = { value ->
                viewModel.sendIntent(MaskingRulesSettingsIntent.KindChanged(value))
            },
            onPatternsInputChange = { value ->
                viewModel.sendIntent(MaskingRulesSettingsIntent.PatternsChanged(value))
            },
            onSave = {
                viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
            },
        )

        MaskingDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(MaskingRulesSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(MaskingRulesSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(MaskingRulesSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        val ruleId = page.ruleId
        if (!page.isCreating && ruleId != null) {
            viewModel.sendIntent(MaskingRulesSettingsIntent.StartEdit(ruleId))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                MaskingRulesSettingsEffect.ExitDetail -> onBack()
                MaskingRulesSettingsEffect.FocusName -> requestedFocusField = MaskingEditableField.Name
                MaskingRulesSettingsEffect.FocusKind -> requestedFocusField = MaskingEditableField.Kind
                MaskingRulesSettingsEffect.FocusPatterns -> requestedFocusField =
                    MaskingEditableField.Patterns
            }
        }
    }
}

@Composable
private fun MaskingRuleDetailContentBody(
    uiState: MaskingRulesSettingsUiState,
    requestedFocusField: MaskingEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onKindChange: (String) -> Unit,
    onPatternsInputChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.masking_rules_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.masking_rules_editor_description),
        inlineErrorText = maskingInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = MaskingEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.masking_rules_field_name),
                value = uiState.formState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.masking_rules_field_name_hint),
                description = maskingFieldErrorText(uiState.formState.nameErrorResId),
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.masking_rules_field_enabled),
                checked = uiState.formState.enabled,
                enabled = !uiState.isSaving,
                onCheckedChange = {
                    fieldController.clearActiveField()
                    onEnabledChange(it)
                },
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = MaskingEditableField.Kind,
                controller = fieldController,
                title = stringResource(R.string.masking_rules_field_kind),
                value = uiState.formState.kind,
                onValueChange = onKindChange,
                placeholder = stringResource(R.string.masking_rules_field_kind_hint),
                description = uiState.formState.kindErrorResId?.let { stringResource(it) }
                    ?: stringResource(R.string.masking_rules_field_kind_description),
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = MaskingEditableField.Patterns,
                controller = fieldController,
                title = stringResource(R.string.masking_rules_field_patterns),
                value = uiState.formState.patternsInput,
                onValueChange = onPatternsInputChange,
                placeholder = stringResource(R.string.masking_rules_field_patterns_hint),
                description = uiState.formState.patternsErrorResId?.let { errorResId ->
                    stringResource(errorResId)
                } ?: stringResource(R.string.masking_rules_field_patterns_description),
                enabled = !uiState.isSaving,
                minLines = 4,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun maskingFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun MaskingDeleteConfirmationDialog(
    state: MaskingRuleDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ZafiroConfirmationDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.masking_rules_delete_dialog_title),
        text = stringResource(R.string.masking_rules_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun MaskingRuleDetailContentPreview() {
    MaterialTheme {
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
            MaskingRuleDetailContentBody(
                uiState = MaskingRulesSettingsUiState(
                    formState = MaskingRuleFormState(
                        name = "default",
                        kind = "API_KEY",
                        patternsInput = """\bsk-[a-zA-Z0-9_\-]{20,}\b""",
                        enabled = true,
                    ),
                    isSaving = false,
                ),
                requestedFocusField = null,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledChange = {},
                onKindChange = {},
                onPatternsInputChange = {},
                onSave = {},
            )
        }
    }
}
