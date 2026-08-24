package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.model.ConfigureEffect
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
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
    var showSaveBeforeProviderPickDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var openProviderPickAfterSave by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ConfigureEffect.SettingsSaveSucceeded -> {
                    if (openProviderPickAfterSave) {
                        openProviderPickAfterSave = false
                        onOpenProviderPick()
                    } else {
                        onBack()
                    }
                }

                ConfigureEffect.FocusModel -> {
                    openProviderPickAfterSave = false
                    pendingFocusField = ConfigureEditableField.Model
                }

                ConfigureEffect.FocusApiKey -> {
                    openProviderPickAfterSave = false
                    pendingFocusField = ConfigureEditableField.ApiKey
                }

                ConfigureEffect.FocusEndpoint -> {
                    openProviderPickAfterSave = false
                    pendingFocusField = ConfigureEditableField.Endpoint
                }

                ConfigureEffect.FocusProxy -> {
                    openProviderPickAfterSave = false
                    pendingFocusField = ConfigureEditableField.Proxy
                }

                ConfigureEffect.OnboardingSaveSucceeded -> Unit
                is ConfigureEffect.SaveFailed -> {
                    openProviderPickAfterSave = false
                }
            }
        }
    }

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = {
            uiState.scene == ConfigureScene.Settings && uiState.hasUnsavedChanges
        },
        onDiscardChanges = onBack,
        rightAction = TopBarActionSpec(
            icon = Icons.Default.Refresh,
            contentDescription = stringResource(R.string.ui_settings_configure_switch_provider),
            onClick = {
                if (uiState.hasUnsavedChanges) {
                    showSaveBeforeProviderPickDialog = true
                } else {
                    onOpenProviderPick()
                }
            },
        ),
    ) {
        ConfigurePageContent(
            uiState = uiState,
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
            onPromptChange = { prompt ->
                viewModel.sendIntent(ConfigureIntent.UpdatePrompt(prompt))
            },
            onProxyChange = { proxy ->
                viewModel.sendIntent(ConfigureIntent.UpdateProxy(proxy))
            },
            onToggleApiKeyVisibility = {
                viewModel.sendIntent(ConfigureIntent.ToggleApiKeyVisibility)
            },
            onComplete = { viewModel.sendIntent(ConfigureIntent.Save) },
            requestedFocusField = pendingFocusField,
            onRequestedFocusHandled = {
                pendingFocusField = null
            },
        )
    }

    ConfirmationLiquidDialog(
        visible = showSaveBeforeProviderPickDialog,
        onDismissRequest = {
            showSaveBeforeProviderPickDialog = false
        },
        title = stringResource(R.string.save_before_provider_pick_dialog_title),
        text = stringResource(R.string.save_before_provider_pick_dialog_text),
        negativeButtonText = stringResource(R.string.save_before_provider_pick_dialog_cancel),
        positiveButtonText = stringResource(R.string.save_before_provider_pick_dialog_save),
        onNegativeClick = {
            showSaveBeforeProviderPickDialog = false
        },
        onPositiveClick = {
            showSaveBeforeProviderPickDialog = false
            openProviderPickAfterSave = true
            viewModel.sendIntent(ConfigureIntent.Save)
        },
    )
}
