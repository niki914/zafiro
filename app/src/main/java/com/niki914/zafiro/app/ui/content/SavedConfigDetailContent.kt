package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureEffect
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.model.EndpointMismatch
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage

/**
 * Saved Configuration 详情页（模板同 McpServerDetailContent）：
 * isCreating = 新建（品牌选择后进入），否则按 configId 加载编辑。
 * 生效中的配置不提供删除入口（右上角无 Delete）。
 */
@Composable
fun SavedConfigDetailContent(
    page: SavedConfigDetailPage,
    onBack: () -> Unit,
    onSaveCompleted: () -> Unit = onBack,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "saved-config-detail:${page.configId ?: page.providerId}",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    var pendingFocusField by rememberSaveable {
        mutableStateOf<ConfigureEditableField?>(null)
    }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(
            if (page.configId == null) {
                ConfigureIntent.Initialize(
                    scene = ConfigureScene.SettingsNew,
                    providerId = page.providerId,
                )
            } else {
                ConfigureIntent.Initialize(
                    scene = ConfigureScene.SettingsEdit,
                    configId = page.configId,
                )
            },
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ConfigureEffect.SettingsSaveSucceeded -> onSaveCompleted()
                ConfigureEffect.ConfigDeleted -> onBack()

                ConfigureEffect.FocusModel -> pendingFocusField = ConfigureEditableField.Model
                ConfigureEffect.FocusApiKey -> pendingFocusField = ConfigureEditableField.ApiKey
                ConfigureEffect.FocusEndpoint -> pendingFocusField = ConfigureEditableField.Endpoint
                ConfigureEffect.FocusProxy -> pendingFocusField = ConfigureEditableField.Proxy

                ConfigureEffect.OnboardingSaveSucceeded,
                is ConfigureEffect.SaveFailed,
                    -> Unit
            }
        }
    }

    // 生效中的配置不提供删除入口
    val isEditingActiveConfig = uiState.editingConfigId != null &&
            uiState.editingConfigId == uiState.activeConfigId

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = { uiState.hasUnsavedChanges },
        onDiscardChanges = onBack,
        onDelete = if (page.isCreating || isEditingActiveConfig) {
            null
        } else {
            { showDeleteConfirmation = true }
        },
        hasDeleteConfirmation = { showDeleteConfirmation },
        onDismissDeleteConfirmation = { showDeleteConfirmation = false },
    ) {
        SavedConfigDetailContentBody(
            uiState = uiState,
            showEndpointOverrideToggle = page.isCreating,
            requestedFocusField = pendingFocusField,
            onRequestedFocusHandled = { pendingFocusField = null },
            onNameChange = { viewModel.sendIntent(ConfigureIntent.UpdateName(it)) },
            onEndpointOverrideChange = { viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(it)) },
            onEndpointChange = { viewModel.sendIntent(ConfigureIntent.UpdateEndpoint(it)) },
            onModelChange = { viewModel.sendIntent(ConfigureIntent.UpdateModel(it)) },
            onApiKeyChange = { viewModel.sendIntent(ConfigureIntent.UpdateApiKey(it)) },
            onProtocolSelected = { viewModel.sendIntent(ConfigureIntent.SelectProtocol(it)) },
            onToggleApiKeyVisibility = { viewModel.sendIntent(ConfigureIntent.ToggleApiKeyVisibility) },
            onProxyChange = { viewModel.sendIntent(ConfigureIntent.UpdateProxy(it)) },
            onSave = { viewModel.sendIntent(ConfigureIntent.Save) },
        )

        EndpointMismatchDialog(
            mismatch = uiState.pendingEndpointMismatch,
            onConfirm = { viewModel.sendIntent(ConfigureIntent.ConfirmEndpointMismatch) },
            onCancel = { viewModel.sendIntent(ConfigureIntent.CancelEndpointMismatch) },
        )

        ConfirmationLiquidDialog(
            visible = showDeleteConfirmation,
            onDismissRequest = { showDeleteConfirmation = false },
            title = stringResource(R.string.ui_settings_saved_configuration_delete_title),
            text = stringResource(R.string.ui_settings_saved_configuration_delete_text),
            negativeButtonText = stringResource(R.string.dialog_cancel),
            positiveButtonText = stringResource(R.string.dialog_confirm_delete),
            onNegativeClick = { showDeleteConfirmation = false },
            onPositiveClick = {
                showDeleteConfirmation = false
                page.configId?.let { viewModel.sendIntent(ConfigureIntent.DeleteConfig(it)) }
            },
        )
    }
}

@Composable
private fun SavedConfigDetailContentBody(
    uiState: ConfigureUiState,
    showEndpointOverrideToggle: Boolean,
    requestedFocusField: ConfigureEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onProxyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    // 编辑态不限制端点编辑；新建态仅部分品牌开放自定义端点
    val policy = if (!showEndpointOverrideToggle) {
        ConfigurePagePolicy(
            showEndpointSection = true,
            showEndpointOverrideToggle = false,
            endpointEditable = true,
        )
    } else {
        onboardingConfigurePolicy(uiState.providerSpec)
    }
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.ui_settings_configure_save),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.ui_settings_configure_description),
        inlineErrorText = configureInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        ProviderAccessSettingsBlock(
            uiState = uiState,
            policy = policy,
            showNameField = true,
            expandedField = fieldController.expandedField,
            onExpandedFieldChange = fieldController.onExpandedFieldChange,
            onNameChange = onNameChange,
            onEndpointOverrideChange = onEndpointOverrideChange,
            onEndpointChange = onEndpointChange,
            onModelChange = onModelChange,
            onApiKeyChange = onApiKeyChange,
            onProtocolSelected = onProtocolSelected,
            onToggleApiKeyVisibility = onToggleApiKeyVisibility,
            onProxyChange = onProxyChange,
            onClearActiveField = fieldController.clearActiveField,
        )
    }
}
