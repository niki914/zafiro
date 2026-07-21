package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SettingExpandableTextCard(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    minLines: Int = 4,
    maxLines: Int = 10,
    secretVisible: Boolean = false,
    onToggleSecretVisibility: (() -> Unit)? = null,
    toggleSecretVisibleContentDescription: String? = null,
    toggleSecretHiddenContentDescription: String? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    SettingsGroupCard(
        title = null,
        modifier = modifier,
    ) {
        SettingExpandableTextItem(
            title = title,
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            description = description,
            enabled = enabled,
            initiallyExpanded = initiallyExpanded,
            expanded = expanded,
            minLines = minLines,
            maxLines = maxLines,
            secretVisible = secretVisible,
            onToggleSecretVisibility = onToggleSecretVisibility,
            toggleSecretVisibleContentDescription = toggleSecretVisibleContentDescription,
            toggleSecretHiddenContentDescription = toggleSecretHiddenContentDescription,
            onExpandedChange = onExpandedChange,
        )
    }
}
