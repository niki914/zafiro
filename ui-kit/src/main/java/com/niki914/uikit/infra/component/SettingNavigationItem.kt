package com.niki914.uikit.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

/**
 * 组内导航子项：保留导航语义，内部统一复用基础设置项底座。
 */
@Composable
fun SettingNavigationItem(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    currentState: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val trailingSlot = if (currentState != null) {
        SettingTrailingSlot.Preview(text = currentState, showChevron = true)
    } else {
        SettingTrailingSlot.Chevron()
    }
    SettingCardItem(
        title = title,
        summary = summary,
        trailingSlot = trailingSlot,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}
