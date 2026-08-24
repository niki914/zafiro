package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.nexus.content.ConfigureEditableField
import com.niki914.zafiro.app.ui.nexus.content.ConfigurePageContent
import com.niki914.zafiro.app.ui.nexus.model.ConfigureEffect
import com.niki914.zafiro.app.ui.nexus.model.ConfigureIntent
import com.niki914.zafiro.app.ui.nexus.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.nexus.nav.ConfigurePage
import com.niki914.zafiro.app.ui.nexus.nav.DonePage
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage

@Composable
internal fun ConfigurePageRoute(
    page: ConfigurePage,
    onPush: (NexusPage) -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = page.providerId,
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    val colors = providerButtonColors(uiState.providerSpec)
    var pendingFocusField by rememberSaveable {
        mutableStateOf<ConfigureEditableField?>(null)
    }

    LaunchedEffect(page.providerId) {
        viewModel.sendIntent(ConfigureIntent.Initialize(page.providerId))
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                ConfigureEffect.OnboardingSaveSucceeded -> onPush(DonePage)
                ConfigureEffect.SettingsSaveSucceeded -> Unit
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

                is ConfigureEffect.SaveFailed -> Unit
            }
        }
    }

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
        onApiKeyChange = { apiKey ->
            viewModel.sendIntent(ConfigureIntent.UpdateApiKey(apiKey))
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
