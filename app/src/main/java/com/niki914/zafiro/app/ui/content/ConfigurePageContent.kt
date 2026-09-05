package com.niki914.zafiro.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsDetailFormScaffold
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureInlineError
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.app.ui.model.EndpointMismatch
import com.niki914.zafiro.app.ui.model.ProviderSpecs
import com.niki914.zafiro.settings.model.LlmProtocol

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
    onProtocolSelected: (String) -> Unit = {},
    onToggleApiKeyVisibility: () -> Unit,
    onProxyChange: (String) -> Unit = {},
    onComplete: () -> Unit,
    onConfirmEndpointMismatch: () -> Unit = {},
    onCancelEndpointMismatch: () -> Unit = {},
    requestedFocusField: ConfigureEditableField? = null,
    onRequestedFocusHandled: () -> Unit = {},
) {
    val policy = onboardingConfigurePolicy(uiState.providerSpec)
    val fieldController = rememberEditableDetailFieldController(
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
    )

    LaunchedEffect(uiState.endpointOverrideEnabled) {
        if (!uiState.endpointOverrideEnabled &&
            fieldController.expandedField == ConfigureEditableField.Endpoint
        ) {
            fieldController.clearActiveField()
        }
    }

    SettingsDetailFormScaffold(
        actionText = stringResource(R.string.ui_onboard_configure_next),
        onActionClick = onComplete,
        description = stringResource(R.string.ui_onboard_configure_description),
        inlineErrorText = configureInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
        onBackgroundTap = fieldController.clearActiveField,
        actionButtonDarkContainerColor = buttonDarkContainerColor,
        actionButtonLightContainerColor = buttonLightContainerColor,
        actionButtonDarkContentColor = buttonDarkContentColor,
        actionButtonLightContentColor = buttonLightContentColor,
    ) {
        ProviderAccessSettingsBlock(
            uiState = uiState,
            policy = policy,
            showNameField = false,
            expandedField = fieldController.expandedField,
            onExpandedFieldChange = fieldController.onExpandedFieldChange,
            onNameChange = {},
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

    EndpointMismatchDialog(
        mismatch = uiState.pendingEndpointMismatch,
        onConfirm = onConfirmEndpointMismatch,
        onCancel = onCancelEndpointMismatch,
    )
}

@Composable
internal fun configureInlineErrorText(error: ConfigureInlineError?): String? {
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

@Composable
internal fun EndpointMismatchDialog(
    mismatch: EndpointMismatch?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (mismatch == null) return
    val isSwitch = mismatch.origin == EndpointMismatch.Origin.SwitchProtocol
    ConfirmationLiquidDialog(
        visible = true,
        onDismissRequest = onCancel,
        title = stringResource(R.string.ui_settings_configure_endpoint_mismatch_title),
        text = stringResource(
            if (isSwitch) {
                R.string.ui_settings_configure_endpoint_mismatch_text_switch
            } else {
                R.string.ui_settings_configure_endpoint_mismatch_text_save
            },
            mismatch.expectedEndpoint,
        ),
        negativeButtonText = stringResource(
            if (isSwitch) {
                R.string.ui_settings_configure_endpoint_mismatch_cancel_keep
            } else {
                R.string.ui_settings_configure_endpoint_mismatch_cancel_save
            },
        ),
        positiveButtonText = stringResource(
            R.string.ui_settings_configure_endpoint_mismatch_confirm_update
        ),
        onNegativeClick = onCancel,
        onPositiveClick = onConfirm,
    )
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
                    scene = ConfigureScene.Onboarding,
                    providerSpec = ProviderSpecs.find("deepseek"),
                    endpointOverrideEnabled = false,
                    endpointInput = ProviderSpecs.find("deepseek").officialEndpoint,
                    modelInput = "deepseek-v4-pro",
                    apiKeyInput = "sk-demo-key",
                    protocolWireId = LlmProtocol.OpenAiResponses.wireId,
                    apiKeyVisible = false,
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
