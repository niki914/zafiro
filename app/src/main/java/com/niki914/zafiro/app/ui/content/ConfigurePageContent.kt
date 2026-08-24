package com.niki914.zafiro.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.model.ConfigureInlineError
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.app.ui.model.ProviderSpecs

@Composable
fun ConfigurePageContent(
    uiState: ConfigureUiState,
    buttonDarkContainerColor: Color = MaterialTheme.colorScheme.primary,
    buttonLightContainerColor: Color = MaterialTheme.colorScheme.primary,
    buttonDarkContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    buttonLightContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onPromptChange: (String) -> Unit = {},
    onProxyChange: (String) -> Unit = {},
    onToggleApiKeyVisibility: () -> Unit,
    onComplete: () -> Unit,
    requestedFocusField: ConfigureEditableField? = null,
    onRequestedFocusHandled: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val policy = configurePagePolicy(uiState.scene, uiState.providerSpec)
    val actionText = stringResource(
        when (uiState.scene) {
            ConfigureScene.Onboarding -> R.string.ui_onboard_configure_next
            ConfigureScene.Settings,
            ConfigureScene.SettingsProviderSwitch -> R.string.ui_settings_configure_save
        },
    )
    val description = stringResource(
        when (uiState.scene) {
            ConfigureScene.Onboarding,
            ConfigureScene.SettingsProviderSwitch -> R.string.ui_onboard_configure_description

            ConfigureScene.Settings -> R.string.ui_settings_configure_description
        },
    )
    var expandedField by rememberSaveable { mutableStateOf<ConfigureEditableField?>(null) }

    fun clearActiveField() {
        expandedField = null
        focusManager.clearFocus()
    }

    LaunchedEffect(uiState.endpointOverrideEnabled) {
        if (!uiState.endpointOverrideEnabled && expandedField == ConfigureEditableField.Endpoint) {
            expandedField = null
        }
    }

    LaunchedEffect(requestedFocusField) {
        if (requestedFocusField != null) {
            expandedField = requestedFocusField
            onRequestedFocusHandled()
        }
    }

    SettingsDetailFormScaffold(
        actionText = actionText,
        onActionClick = onComplete,
        description = description,
        inlineErrorText = configureInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
        onBackgroundTap = ::clearActiveField,
        actionButtonDarkContainerColor = buttonDarkContainerColor,
        actionButtonLightContainerColor = buttonLightContainerColor,
        actionButtonDarkContentColor = buttonDarkContentColor,
        actionButtonLightContentColor = buttonLightContentColor,
    ) {
        ProviderAccessSettingsBlock(
            uiState = uiState,
            policy = policy,
            expandedField = expandedField,
            onExpandedFieldChange = { field -> expandedField = field },
            onEndpointOverrideChange = onEndpointOverrideChange,
            onEndpointChange = onEndpointChange,
            onModelChange = onModelChange,
            onApiKeyChange = onApiKeyChange,
            onToggleApiKeyVisibility = onToggleApiKeyVisibility,
            onClearActiveField = ::clearActiveField,
        )
        if (policy.showAdvancedSection) {
            ProviderAdvancedSettingsBlock(
                uiState = uiState,
                expandedField = expandedField,
                onExpandedFieldChange = { field -> expandedField = field },
                onPromptChange = onPromptChange,
                onProxyChange = onProxyChange,
            )
        }
    }
}

@Composable
private fun configureInlineErrorText(error: ConfigureInlineError?): String? {
    return when (error) {
        null -> null
        is ConfigureInlineError.LoadFailed -> stringResource(
            R.string.ui_onboard_configure_error_load_failed,
            error.reason.message,
        )

        is ConfigureInlineError.SaveFailed -> stringResource(
            R.string.ui_onboard_configure_error_save_failed,
            error.reason.message,
        )
    }
}

@Preview(
    name = "Configure Page Preview",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun ConfigurePageContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ConfigurePageContent(
                uiState = ConfigureUiState(
                    providerSpec = ProviderSpecs.find("deepseek"),
                    endpointOverrideEnabled = false,
                    endpointInput = ProviderSpecs.find("deepseek").officialEndpoint,
                    modelInput = "deepseek-chat",
                    apiKeyInput = "sk-demo-key",
                    apiKeyVisible = false
                ),
                onEndpointOverrideChange = {},
                onEndpointChange = {},
                onModelChange = {},
                onApiKeyChange = {},
                onToggleApiKeyVisibility = {},
                onComplete = {},
            )
        }
    }
}
