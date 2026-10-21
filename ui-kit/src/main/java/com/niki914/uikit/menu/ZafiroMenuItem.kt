package com.niki914.uikit.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.niki914.uikit.infra.LiquidMenuItem

@Composable
fun ZafiroMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
) {
    LiquidMenuItem(
        title = title,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        checked = checked,
        leadingIcon = leadingIcon,
        trailingText = trailingText,
    )
}
