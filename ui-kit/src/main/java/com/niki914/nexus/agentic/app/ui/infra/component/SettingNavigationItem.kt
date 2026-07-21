package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 组内导航子项：保留导航语义，内部统一复用基础列表项。
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
    SettingsListItem(
        title = title,
        summary = summary,
        modifier = modifier,
        currentState = currentState,
        enabled = enabled,
        showChevron = true,
        onClick = onClick,
    )
}
