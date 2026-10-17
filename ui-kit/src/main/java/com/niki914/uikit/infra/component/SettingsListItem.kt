package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingLeadingSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

/**
 * 基础列表项组件：现已全面统一接入 [SettingCardItem] 底座与 Slot 体系。
 * 彻底消除 BoxWithConstraints 与 maxWidth * 0.66f 硬编码限制。
 */
@Composable
fun SettingsListItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    currentState: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val leadingSlot = if (leadingContent != null) {
        SettingLeadingSlot.Custom(leadingContent)
    } else {
        SettingLeadingSlot.None
    }

    val trailingSlot = when {
        trailingContent != null -> SettingTrailingSlot.Custom(trailingContent)
        currentState != null -> SettingTrailingSlot.Preview(
            text = currentState,
            showChevron = showChevron,
        )
        showChevron -> SettingTrailingSlot.Chevron()
        else -> SettingTrailingSlot.None
    }

    SettingCardItem(
        title = title,
        summary = summary,
        leadingSlot = leadingSlot,
        trailingSlot = trailingSlot,
        enabled = enabled,
        highlightPulseKey = highlightPulseKey,
        highlightPulseDurationMillis = highlightPulseDurationMillis,
        onClick = onClick,
        modifier = modifier,
    )
}
