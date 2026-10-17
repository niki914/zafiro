package com.niki914.uikit.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

/**
 * 独立开关卡片组件：现已全面统一接入 [SettingCardItem] 底座。
 */
@Composable
fun SettingsToggleListItemCard(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingCardItem(
        title = title,
        summary = summary,
        trailingSlot = SettingTrailingSlot.Toggle(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        ),
        enabled = enabled,
        isStandaloneCard = true,
        onClick = onClick,
        modifier = modifier,
    )
}
