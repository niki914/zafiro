package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.content.ConfigureEditableField
import com.niki914.zafiro.app.ui.content.ConfigurePageContent
import com.niki914.zafiro.app.ui.content.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.model.ConfigureEffect
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.SettingsConfigurePage

/**
 * 设置页 Add 流程：品牌选择后进入新建配置表单。
 * 本页面与 ModelConfig 页共享 Store? 不——pageViewModel 按 NavigationEntry 隔离，
 * 各自持有独立 ConfigureViewModel；保存成功后返回，ModelConfig 页重新组合时
 * 重新 Initialize 刷新列表。
 */
@Composable
internal fun SettingsConfigurePageRoute(
    page: SettingsConfigurePage,
    onBack: () -> Unit,
    onSaveCompleted: () -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "settings-configure-new:${page.providerId}",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    val colors = providerButtonColors(uiState.providerSpec)
    var pendingFocusField by rememberSaveable {
        mutableStateOf<ConfigureEditableField?>(null)
    }

    LaunchedEffect(page.providerId) {
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                scene = ConfigureScene.SettingsNew,
                providerId = page.providerId,
            ),
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ConfigureEffect.SettingsSaveSucceeded -> onSaveCompleted()
                ConfigureEffect.FocusModel -> {
                    pendingFocusField = ConfigureEditableField.Model
                }

                ConfigureEffect.FocusApiKey -> {
                    pendingFocusField = ConfigureEditableField.ApiKey
                }

                ConfigureEffect.FocusEndpoint -> {
                    pendingFocusField = ConfigureEditableField.Endpoint
                }

                ConfigureEffect.FocusProxy -> {
                    pendingFocusField = ConfigureEditableField.Proxy
                }

                ConfigureEffect.OnboardingSaveSucceeded,
                ConfigureEffect.AllConfigsDeleted,
                is ConfigureEffect.SaveFailed,
                -> Unit
            }
        }
    }

    EditableSettingsDetailChrome(
        isCreating = true,
        hasUnsavedChanges = { uiState.hasUnsavedChanges },
        onDiscardChanges = onBack,
    ) {
        ConfigurePageContent(
        uiState = uiState,
        buttonDarkContainerColor = colors.darkContainerColor,
        buttonLightContainerColor = colors.lightContainerColor,
        buttonDarkContentColor = colors.darkContentColor,
        buttonLightContentColor = colors.lightContentColor,
        onEndpointOverrideChange = { enabled ->
            viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(enabled))
        },
        onEndpointChange = { endpoint ->
            viewModel.sendIntent(ConfigureIntent.UpdateEndpoint(endpoint))
        },
        onModelChange = { model ->
            viewModel.sendIntent(ConfigureIntent.UpdateModel(model))
        },
        onNameChange = { value ->
            viewModel.sendIntent(ConfigureIntent.UpdateName(value))
        },
        onApiKeyChange = { apiKey ->
            viewModel.sendIntent(ConfigureIntent.UpdateApiKey(apiKey))
        },
        onProtocolSelected = { wireId ->
            viewModel.sendIntent(ConfigureIntent.SelectProtocol(wireId))
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
}
