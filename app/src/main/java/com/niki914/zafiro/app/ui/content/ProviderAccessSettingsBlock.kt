package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.SettingExpandableTextItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.settings.model.LlmProtocol

@Composable
internal fun ProviderAccessSettingsBlock(
    uiState: ConfigureUiState,
    policy: ConfigurePagePolicy,
    showNameField: Boolean,
    expandedField: ConfigureEditableField?,
    onExpandedFieldChange: (ConfigureEditableField?) -> Unit,
    onNameChange: (String) -> Unit,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProtocolSelected: (String) -> Unit = {},
    onToggleApiKeyVisibility: () -> Unit,
    onProxyChange: (String) -> Unit,
    onClearActiveField: () -> Unit,
) {
    var showProtocolDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupCard {
        if (showNameField) {
            SettingExpandableTextItem(
                title = stringResource(R.string.ui_settings_configure_name_label),
                value = uiState.configNameInput,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.ui_settings_configure_name_placeholder),
                description = uiState.nameErrorResId?.let { stringResource(it) },
                minLines = 1,
                maxLines = 1,
                expanded = expandedField == ConfigureEditableField.Name,
                onExpandedChange = { isExpanded ->
                    onExpandedFieldChange(
                        if (isExpanded) ConfigureEditableField.Name else null,
                    )
                },
            )
            SettingsItemDivider()
        }
        if (policy.showEndpointSection) {
            val endpointEditable = policy.endpointEditable &&
                    (!policy.showEndpointOverrideToggle || uiState.endpointOverrideEnabled)
            if (policy.showEndpointOverrideToggle) {
                SettingToggleItem(
                    title = stringResource(R.string.ui_onboard_configure_endpoint_override_title),
                    description = stringResource(
                        if (uiState.endpointOverrideEnabled) {
                            R.string.ui_onboard_configure_endpoint_override_description_on
                        } else {
                            R.string.ui_onboard_configure_endpoint_override_description_off
                        },
                    ),
                    checked = uiState.endpointOverrideEnabled,
                    enabled = !uiState.isSaving,
                    onCheckedChange = { enabled ->
                        if (!uiState.isSaving) {
                            onClearActiveField()
                            onEndpointOverrideChange(enabled)
                        }
                    },
                )
                SettingsItemDivider()
            }
            SettingExpandableTextItem(
                title = stringResource(R.string.ui_onboard_configure_endpoint_label),
                value = uiState.endpointInput,
                onValueChange = onEndpointChange,
                placeholder = stringResource(R.string.ui_onboard_configure_endpoint_placeholder),
                description = uiState.endpointErrorResId?.let { stringResource(it) },
                enabled = endpointEditable && !uiState.isSaving,
                minLines = 3,
                maxLines = 6,
                expanded = expandedField == ConfigureEditableField.Endpoint,
                onExpandedChange = { isExpanded ->
                    onExpandedFieldChange(
                        if (isExpanded) ConfigureEditableField.Endpoint else null,
                    )
                },
            )
            SettingsItemDivider()
        }
        SettingExpandableTextItem(
            title = stringResource(R.string.ui_onboard_configure_model_label),
            value = uiState.modelInput,
            onValueChange = onModelChange,
            placeholder = stringResource(R.string.ui_onboard_configure_model_placeholder),
            description = uiState.modelErrorResId?.let { stringResource(it) }
                ?: if (uiState.modelInput.isBlank()) {
                    stringResource(
                        R.string.ui_onboard_configure_model_example,
                        uiState.providerSpec.exampleModelId,
                    )
                } else {
                    null
                },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            expanded = expandedField == ConfigureEditableField.Model,
            onExpandedChange = { isExpanded ->
                onExpandedFieldChange(
                    if (isExpanded) ConfigureEditableField.Model else null,
                )
            },
        )
        SettingsItemDivider()
        // 协议行：与 About version 行同款 Value Row 组件（点击弹选择弹窗）
        SettingsListItem(
            title = stringResource(R.string.ui_settings_configure_protocol_label),
            currentState = uiState.protocolWireId,
            showChevron = true,
            onClick = {
                onClearActiveField()
                showProtocolDialog = true
            },
        )
        SettingsItemDivider()
        SettingExpandableTextItem(
            title = stringResource(R.string.ui_onboard_configure_api_key_label),
            value = uiState.apiKeyInput,
            onValueChange = onApiKeyChange,
            placeholder = stringResource(R.string.ui_onboard_configure_api_key_placeholder),
            description = uiState.apiKeyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            secretVisible = uiState.apiKeyVisible,
            onToggleSecretVisibility = onToggleApiKeyVisibility,
            toggleSecretVisibleContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_show,
            ),
            toggleSecretHiddenContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_hide,
            ),
            expanded = expandedField == ConfigureEditableField.ApiKey,
            onExpandedChange = { isExpanded ->
                onExpandedFieldChange(
                    if (isExpanded) ConfigureEditableField.ApiKey else null,
                )
            },
        )
        SettingsItemDivider()
        SettingExpandableTextItem(
            title = stringResource(R.string.ui_settings_configure_proxy_label),
            value = uiState.proxyInput,
            onValueChange = onProxyChange,
            placeholder = stringResource(R.string.ui_settings_configure_proxy_placeholder),
            description = uiState.proxyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            expanded = expandedField == ConfigureEditableField.Proxy,
            onExpandedChange = { isExpanded ->
                onExpandedFieldChange(
                    if (isExpanded) ConfigureEditableField.Proxy else null,
                )
            },
        )
    }

    // "deepseek" 协议与 openai-chat-completions 同壳，仅作存量存储值兼容，不再提供新选
    val selectableProtocols = LlmProtocol.entries.filter { it != LlmProtocol.DeepSeek }
    SingleChoiceLiquidDialog(
        visible = showProtocolDialog,
        onDismissRequest = { showProtocolDialog = false },
        title = stringResource(R.string.ui_settings_configure_protocol_label),
        hint = stringResource(R.string.ui_settings_configure_protocol_hint),
        options = selectableProtocols,
        selectedId = uiState.protocolWireId,
        optionId = LlmProtocol::wireId,
        optionLabel = LlmProtocol::wireId,
        onSelect = { protocol ->
            showProtocolDialog = false
            onProtocolSelected(protocol.wireId)
        },
    )
}
