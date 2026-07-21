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
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsItemDivider
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsSegmentedSelector
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRuleDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRuleFormState
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.hasUnsavedChanges
import com.niki914.nexus.agentic.app.ui.nexus.nav.ExecutionRuleDetailPage
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode

@Composable
fun ExecutionRuleDetailContent(
    page: ExecutionRuleDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<ExecutionRulesSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable {
        mutableStateOf<ExecutionRuleEditableField?>(null)
    }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDiscardChanges = onBack,
        onDelete = {
            viewModel.sendIntent(ExecutionRulesSettingsIntent.RequestDelete)
        },
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(ExecutionRulesSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        ExecutionRuleDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(ExecutionRulesSettingsIntent.NameChanged(value))
            },
            onEnabledModeChange = { value ->
                viewModel.sendIntent(ExecutionRulesSettingsIntent.EnabledModeChanged(value))
            },
            onPatternsInputChange = { value ->
                viewModel.sendIntent(ExecutionRulesSettingsIntent.PatternsChanged(value))
            },
            onSave = {
                viewModel.sendIntent(ExecutionRulesSettingsIntent.Save)
            },
        )

        ExecutionRuleDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(ExecutionRulesSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(ExecutionRulesSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(ExecutionRulesSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.ruleIndex in uiState.items.indices) {
            viewModel.sendIntent(ExecutionRulesSettingsIntent.StartEdit(page.ruleIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ExecutionRulesSettingsEffect.ExitDetail -> onBack()
                ExecutionRulesSettingsEffect.FocusName -> {
                    requestedFocusField = ExecutionRuleEditableField.Name
                }

                ExecutionRulesSettingsEffect.FocusPatterns -> {
                    requestedFocusField = ExecutionRuleEditableField.Patterns
                }
            }
        }
    }
}

private enum class ExecutionRuleEditableField {
    Name,
    Patterns,
}

@Composable
private fun ExecutionRuleDetailContentBody(
    uiState: ExecutionRulesSettingsUiState,
    requestedFocusField: ExecutionRuleEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledModeChange: (RuntimeExecutionRuleEnabledMode) -> Unit,
    onPatternsInputChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.execution_rules_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.execution_rules_editor_description),
        inlineErrorText = executionRulesInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = ExecutionRuleEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.execution_rules_field_name),
                value = uiState.formState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.execution_rules_field_name_hint),
                description = executionRulesFieldErrorText(uiState.formState.nameErrorResId),
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            ExecutionRuleEnabledModeSection(
                selectedMode = uiState.formState.enabledMode,
                enabled = !uiState.isSaving,
                onModeSelected = {
                    fieldController.clearActiveField()
                    onEnabledModeChange(it)
                },
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = ExecutionRuleEditableField.Patterns,
                controller = fieldController,
                title = stringResource(R.string.execution_rules_field_patterns),
                value = uiState.formState.patternsInput,
                onValueChange = onPatternsInputChange,
                placeholder = stringResource(R.string.execution_rules_field_patterns_hint),
                description = executionRulesFieldErrorText(uiState.formState.patternsErrorResId)
                    ?: stringResource(R.string.execution_rules_field_patterns_description),
                enabled = !uiState.isSaving,
                minLines = 4,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun ExecutionRuleEnabledModeSection(
    selectedMode: RuntimeExecutionRuleEnabledMode,
    onModeSelected: (RuntimeExecutionRuleEnabledMode) -> Unit,
    enabled: Boolean = true,
) {
    SettingsSegmentedSelector(
        title = stringResource(R.string.execution_rules_field_enabled_mode),
        options = RuntimeExecutionRuleEnabledMode.entries,
        selected = selectedMode,
        label = { option -> stringResource(option.labelRes()) },
        onSelected = onModeSelected,
        enabled = enabled,
    )
}

@Composable
private fun executionRulesFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun executionRulesInlineErrorText(error: ExecutionRulesInlineError?): String? {
    return when (error) {
        null -> null
        is ExecutionRulesInlineError.LoadFailed -> error.message
            ?: stringResource(error.fallbackResId)

        is ExecutionRulesInlineError.SaveFailed -> error.message
            ?: stringResource(error.fallbackResId)

        is ExecutionRulesInlineError.DeleteFailed -> error.message
            ?: stringResource(error.fallbackResId)
    }
}

private fun RuntimeExecutionRuleEnabledMode.labelRes(): Int {
    return when (this) {
        RuntimeExecutionRuleEnabledMode.ALWAYS -> R.string.execution_rules_enabled_mode_enabled
        RuntimeExecutionRuleEnabledMode.LOCKED_ONLY -> R.string.execution_rules_enabled_mode_locked_only
        RuntimeExecutionRuleEnabledMode.DISABLED -> R.string.execution_rules_enabled_mode_disabled
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ExecutionRuleDetailContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ExecutionRuleDetailContentBody(
                uiState = ExecutionRulesSettingsUiState(
                    formState = ExecutionRuleFormState(
                        name = "危险删改",
                        enabledMode = RuntimeExecutionRuleEnabledMode.LOCKED_ONLY,
                        patternsInput = """
                            \brm\s+-rf\b
                            \bmkfs\b
                            pm uninstall
                        """.trimIndent(),
                    ),
                    isLoading = false,
                    isSaving = false,
                ),
                requestedFocusField = null,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledModeChange = {},
                onPatternsInputChange = {},
                onSave = {},
            )
        }
    }
}

@Composable
private fun ExecutionRuleDeleteConfirmationDialog(
    state: ExecutionRuleDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.execution_rules_delete_dialog_title),
        text = stringResource(R.string.execution_rules_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
