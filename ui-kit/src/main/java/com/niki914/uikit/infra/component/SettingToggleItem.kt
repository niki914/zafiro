package com.niki914.uikit.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot
import com.niki914.uikit.infra.shape.G2CardShape

/**
 * 组内开关子项：保持整行切换和透明 item 语义，由外层容器提供卡片边界。
 * 现已全面统一接入 [SettingCardItem] 与 [SettingTrailingSlot.Toggle]。
 */
@Composable
fun SettingToggleItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    shape: Shape = G2CardShape(SettingsTokens.CardCornerRadius),
    isStandaloneCard: Boolean = false,
) {
    SettingCardItem(
        title = title,
        summary = description,
        trailingSlot = SettingTrailingSlot.Toggle(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        ),
        enabled = enabled,
        shape = shape,
        isStandaloneCard = isStandaloneCard,
        modifier = modifier,
    )
}
