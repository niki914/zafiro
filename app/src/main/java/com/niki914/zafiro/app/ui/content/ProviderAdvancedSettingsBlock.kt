package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.SettingExpandableTextItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.zafiro.app.ui.model.ConfigureUiState

@Composable
internal fun ProviderAdvancedSettingsBlock(
    uiState: ConfigureUiState,
    expandedField: ConfigureEditableField?,
    onExpandedFieldChange: (ConfigureEditableField?) -> Unit,
    onPromptChange: (String) -> Unit,
    onProxyChange: (String) -> Unit,
) {
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
            expanded = expandedField == ConfigureEditableField.Prompt,
            onExpandedChange = { isExpanded ->
                onExpandedFieldChange(
                    if (isExpanded) ConfigureEditableField.Prompt else null,
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
}
