package com.niki914.nexus.agentic.app.ui.nexus.content.mcp

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
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.component.SettingToggleItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsItemDivider
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.content.EditableDetailFieldController
import com.niki914.nexus.agentic.app.ui.nexus.content.EditableSettingsDetailChrome
import com.niki914.nexus.agentic.app.ui.nexus.content.EditableSettingsDetailFormScaffold
import com.niki914.nexus.agentic.app.ui.nexus.content.SettingControlledExpandableTextItem
import com.niki914.nexus.agentic.app.ui.nexus.model.McpDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.McpInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.McpServerFormState
import com.niki914.nexus.agentic.app.ui.nexus.model.McpSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.McpSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.McpSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.McpSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.hasUnsavedChanges
import com.niki914.nexus.agentic.app.ui.nexus.nav.McpServerDetailPage

@Composable
fun McpServerDetailContent(
    page: McpServerDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<McpSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable { mutableStateOf<McpEditableField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(McpSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(McpSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        McpServerDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(McpSettingsIntent.NameChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(McpSettingsIntent.EnabledChanged(value))
            },
            onUrlChange = { value ->
                viewModel.sendIntent(McpSettingsIntent.UrlChanged(value))
            },
            onHeadersChange = { value ->
                viewModel.sendIntent(McpSettingsIntent.HeadersChanged(value))
            },
            onSave = {
                viewModel.sendIntent(McpSettingsIntent.Save)
            },
        )

        McpDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(McpSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(McpSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(McpSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(McpSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.serverIndex in uiState.items.indices) {
            viewModel.sendIntent(McpSettingsIntent.StartEdit(page.serverIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                McpSettingsEffect.ExitDetail -> onBack()
                McpSettingsEffect.FocusName -> requestedFocusField = McpEditableField.Name
                McpSettingsEffect.FocusUrl -> requestedFocusField = McpEditableField.Url
                McpSettingsEffect.FocusHeaders -> requestedFocusField = McpEditableField.Headers
            }
        }
    }
}

@Composable
private fun McpServerDetailContentBody(
    uiState: McpSettingsUiState,
    requestedFocusField: McpEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onHeadersChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.mcp_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.mcp_page_description),
        inlineErrorText = mcpInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        McpIdentitySettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onNameChange = onNameChange,
            onEnabledChange = {
                fieldController.clearActiveField()
                onEnabledChange(it)
            },
        )

        McpConnectionSettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onUrlChange = onUrlChange,
            onHeadersChange = onHeadersChange,
        )
    }
}

private enum class McpEditableField {
    Name,
    Url,
    Headers,
}

@Composable
private fun McpIdentitySettingsBlock(
    uiState: McpSettingsUiState,
    fieldController: EditableDetailFieldController<McpEditableField>,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = McpEditableField.Name,
            controller = fieldController,
            title = stringResource(R.string.mcp_field_name),
            value = uiState.formState.name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.mcp_field_name_hint),
            description = mcpFieldErrorText(uiState.formState.nameErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingToggleItem(
            title = stringResource(R.string.mcp_field_enabled),
            checked = uiState.formState.enabled,
            enabled = !uiState.isSaving,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun McpConnectionSettingsBlock(
    uiState: McpSettingsUiState,
    fieldController: EditableDetailFieldController<McpEditableField>,
    onUrlChange: (String) -> Unit,
    onHeadersChange: (String) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = McpEditableField.Url,
            controller = fieldController,
            title = stringResource(R.string.mcp_field_url),
            value = uiState.formState.url,
            onValueChange = onUrlChange,
            placeholder = stringResource(R.string.mcp_field_url_hint),
            description = mcpFieldErrorText(uiState.formState.urlErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingControlledExpandableTextItem(
            field = McpEditableField.Headers,
            controller = fieldController,
            title = stringResource(R.string.mcp_field_headers),
            value = uiState.formState.headersInput,
            onValueChange = onHeadersChange,
            placeholder = stringResource(R.string.mcp_field_headers_hint),
            description = mcpFieldErrorText(uiState.formState.headersErrorResId),
            enabled = !uiState.isSaving,
            minLines = 4,
            maxLines = 10,
        )
    }
}

@Composable
private fun mcpFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun mcpInlineErrorText(error: McpInlineError?): String? {
    return when (error) {
        null -> null
        is McpInlineError.LoadFailed -> error.message ?: stringResource(error.fallbackResId)
        is McpInlineError.SaveFailed -> error.message ?: stringResource(error.fallbackResId)
        is McpInlineError.DeleteFailed -> error.message ?: stringResource(error.fallbackResId)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun McpServerDetailContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            McpServerDetailContentBody(
                uiState = McpSettingsUiState(
                    items = emptyList(),
                    formState = McpServerFormState(
                        name = "demo-mcp",
                        url = "https://a.b.c/mcp",
                        enabled = true,
                        headersInput = "{\n  \"Authorization\": \"Bearer xxx\"\n}",
                        headersErrorResId = R.string.mcp_error_headers_not_object,
                    ),
                    isLoading = false,
                    isSaving = false,
                ),
                requestedFocusField = McpEditableField.Headers,
                onRequestedFocusHandled = {},
                onNameChange = {},
                onEnabledChange = {},
                onUrlChange = {},
                onHeadersChange = {},
                onSave = {},
            )
        }
    }
}

@Composable
private fun McpDeleteConfirmationDialog(
    state: McpDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.mcp_delete_dialog_title),
        text = stringResource(R.string.mcp_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
