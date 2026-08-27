package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.niki914.zafiro.app.ui.model.ConfigureEffect
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

@Composable
fun ModelConfigSettingsContent(
    onBack: () -> Unit,
    onOpenProviderPick: () -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "settings-configure",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    var pendingFocusField by rememberSaveable {
        mutableStateOf<ConfigureEditableField?>(null)
    }
    var showUnsavedBeforeAdd by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteConfigId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                scene = ConfigureScene.SettingsEdit,
            ),
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ConfigureEffect.SettingsSaveSucceeded -> Unit // 保存后留在本页（列表/表单已刷新）
                ConfigureEffect.AllConfigsDeleted -> onBack()

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

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { uiState.hasUnsavedChanges },
        onDiscardChanges = onBack,
        rightAction = TopBarActionSpec(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.ui_settings_configure_add),
            onClick = {
                if (uiState.hasUnsavedChanges) {
                    showUnsavedBeforeAdd = true
                } else {
                    onOpenProviderPick()
                }
            },
        ),
    ) {
        ConfigurePageContent(
            uiState = uiState,
            onNameChange = { value ->
                viewModel.sendIntent(ConfigureIntent.UpdateName(value))
            },
            onEndpointOverrideChange = { enabled ->
                viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(enabled))
            },
            onEndpointChange = { endpoint ->
                viewModel.sendIntent(ConfigureIntent.UpdateEndpoint(endpoint))
            },
            onModelChange = { model ->
                viewModel.sendIntent(ConfigureIntent.UpdateModel(model))
            },
            onApiKeyChange = { apiKey ->
                viewModel.sendIntent(ConfigureIntent.UpdateApiKey(apiKey))
            },
            onProtocolSelected = { wireId ->
                viewModel.sendIntent(ConfigureIntent.SelectProtocol(wireId))
            },
            onToggleApiKeyVisibility = {
                viewModel.sendIntent(ConfigureIntent.ToggleApiKeyVisibility)
            },
            onPromptChange = { prompt ->
                viewModel.sendIntent(ConfigureIntent.UpdatePrompt(prompt))
            },
            onProxyChange = { proxy ->
                viewModel.sendIntent(ConfigureIntent.UpdateProxy(proxy))
            },
            onComplete = { viewModel.sendIntent(ConfigureIntent.Save) },
            requestedFocusField = pendingFocusField,
            onRequestedFocusHandled = {
                pendingFocusField = null
            },
            onEditSavedConfig = { configId ->
                viewModel.sendIntent(
                    ConfigureIntent.Initialize(
                        scene = ConfigureScene.SettingsEdit,
                        configId = configId,
                    ),
                )
            },
            onActivateSavedConfig = { configId ->
                viewModel.sendIntent(ConfigureIntent.ActivateConfig(configId))
            },
            onDeleteSavedConfig = { configId ->
                pendingDeleteConfigId = configId
            },
        )
    }

    ConfirmationLiquidDialog(
        visible = showUnsavedBeforeAdd,
        onDismissRequest = { showUnsavedBeforeAdd = false },
        title = stringResource(R.string.unsaved_changes_dialog_title),
        text = stringResource(R.string.unsaved_changes_dialog_text),
        negativeButtonText = stringResource(R.string.unsaved_changes_dialog_cancel),
        positiveButtonText = stringResource(R.string.unsaved_changes_dialog_confirm_exit),
        onNegativeClick = { showUnsavedBeforeAdd = false },
        onPositiveClick = {
            showUnsavedBeforeAdd = false
            onOpenProviderPick()
        },
    )

    ConfirmationLiquidDialog(
        visible = pendingDeleteConfigId != null,
        onDismissRequest = { pendingDeleteConfigId = null },
        title = stringResource(R.string.ui_settings_saved_configuration_delete_title),
        text = stringResource(R.string.ui_settings_saved_configuration_delete_text),
        negativeButtonText = stringResource(R.string.dialog_cancel),
        positiveButtonText = stringResource(R.string.dialog_confirm_delete),
        onNegativeClick = { pendingDeleteConfigId = null },
        onPositiveClick = {
            pendingDeleteConfigId?.let { configId ->
                viewModel.sendIntent(ConfigureIntent.DeleteConfig(configId))
            }
            pendingDeleteConfigId = null
        },
    )
}
