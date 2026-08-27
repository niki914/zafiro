package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsDetailFormScaffold
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingExpandableTextItem
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
    onNameChange: (String) -> Unit = {},
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProtocolSelected: (String) -> Unit = {},
    onToggleApiKeyVisibility: () -> Unit,
    onPromptChange: (String) -> Unit = {},
    onProxyChange: (String) -> Unit = {},
    onEditSavedConfig: (String) -> Unit = {},
    onActivateSavedConfig: (String) -> Unit = {},
    onDeleteSavedConfig: (String) -> Unit = {},
    onComplete: () -> Unit,
    requestedFocusField: ConfigureEditableField? = null,
    onRequestedFocusHandled: () -> Unit = {},
) {
    val policy = configurePagePolicy(uiState.scene, uiState.providerSpec)
    val actionText = stringResource(
        when (uiState.scene) {
            ConfigureScene.Onboarding -> R.string.ui_onboard_configure_next
            ConfigureScene.SettingsNew,
            ConfigureScene.SettingsEdit,
            -> R.string.ui_settings_configure_save
        },
    )
    val description = stringResource(
        when (uiState.scene) {
            ConfigureScene.Onboarding -> R.string.ui_onboard_configure_description
            ConfigureScene.SettingsNew,
            ConfigureScene.SettingsEdit,
            -> R.string.ui_settings_configure_description
        },
    )
    val fieldController = rememberEditableDetailFieldController(
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
    )
    var showProtocolDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.endpointOverrideEnabled) {
        if (!uiState.endpointOverrideEnabled &&
            fieldController.expandedField == ConfigureEditableField.Endpoint
        ) {
            fieldController.clearActiveField()
        }
    }

    SettingsDetailFormScaffold(
        actionText = actionText,
        onActionClick = onComplete,
        description = description,
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
            expandedField = fieldController.expandedField,
            onExpandedFieldChange = fieldController.onExpandedFieldChange,
            onNameChange = onNameChange,
            onEndpointOverrideChange = onEndpointOverrideChange,
            onEndpointChange = onEndpointChange,
            onModelChange = onModelChange,
            onApiKeyChange = onApiKeyChange,
            // 打开弹窗前先收起编辑区焦点/软键盘
            onProtocolClick = {
                fieldController.clearActiveField()
                showProtocolDialog = true
            },
            onToggleApiKeyVisibility = onToggleApiKeyVisibility,
            onProxyChange = onProxyChange,
            onClearActiveField = fieldController.clearActiveField,
        )

        // ── System Prompt / Saved Configuration：仅编辑既有配置时展示 ──
        if (uiState.scene == ConfigureScene.SettingsEdit) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingsGroupCard {
                    SettingExpandableTextItem(
                        title = stringResource(R.string.ui_settings_configure_prompt_label),
                        value = uiState.promptInput,
                        onValueChange = onPromptChange,
                        placeholder = stringResource(R.string.ui_settings_configure_prompt_placeholder),
                        description = null,
                        enabled = !uiState.isSaving,
                        minLines = 3,
                        maxLines = 8,
                        expanded = fieldController.expandedField == ConfigureEditableField.Prompt,
                        onExpandedChange = { isExpanded ->
                            fieldController.onExpandedFieldChange(
                                if (isExpanded) ConfigureEditableField.Prompt else null,
                            )
                        },
                    )
                }

                SavedConfigurationBlock(
                    configs = uiState.savedConfigs.map { summary ->
                        summary.copy(isActive = summary.id == uiState.activeConfigId)
                    },
                    onEditClick = { configId ->
                        fieldController.clearActiveField()
                        onEditSavedConfig(configId)
                    },
                    onActivateClick = onActivateSavedConfig,
                    onDeleteRequest = onDeleteSavedConfig,
                )
            }
        }
    }

    SingleChoiceLiquidDialog(
        visible = showProtocolDialog,
        onDismissRequest = { showProtocolDialog = false },
        title = stringResource(R.string.ui_settings_configure_protocol_label),
        hint = stringResource(R.string.ui_settings_configure_protocol_hint),
        options = LlmProtocol.entries.toList(),
        selectedId = uiState.protocolWireId,
        optionId = LlmProtocol::wireId,
        optionLabel = LlmProtocol::wireId,
        onSelect = { protocol ->
            showProtocolDialog = false
            onProtocolSelected(protocol.wireId)
        },
    )
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
