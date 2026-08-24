package com.niki914.zafiro.app.ui.nexus.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.SettingExpandableTextItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.zafiro.app.ui.nexus.model.ConfigureUiState

@Composable
internal fun ProviderAccessSettingsBlock(
    uiState: ConfigureUiState,
    policy: ConfigurePagePolicy,
    expandedField: ConfigureEditableField?,
    onExpandedFieldChange: (ConfigureEditableField?) -> Unit,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onClearActiveField: () -> Unit,
) {
    SettingsGroupCard {
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
                description = uiState.endpointErrorResId?.let { stringResource(it) }
                    ?: if (endpointEditable) {
                        null
                    } else {
                        uiState.endpointInput
                    },
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
    }
}
