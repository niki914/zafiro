package com.niki914.uikit.infra.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.shape.G2FieldShape

/**
 * Zafiro 规范胶囊 Chip 基建：
 * - 采用 G2 曲率剪裁（G2FieldShape）；
 * - 选中项与非选中项仅存在颜色区别（选中的项和其他项相比只有颜色的区别，无图标或布局跳变）；
 * - 集成点击触感反馈（ContextClick）；
 * - 依据主题风格（LiquidGlass / Material3 / Expressive）自动适配色彩表现。
 */
@Composable
fun ZafiroChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 56.dp,
) {
    val haptics = LocalHapticFeedback.current
    val themeStyle = LocalThemeStyle.current
    val interactionSource = remember { MutableInteractionSource() }

    val targetBgColor = when {
        selected -> when (themeStyle) {
            ThemeStyle.LiquidGlass -> MaterialTheme.colorScheme.primaryContainer
            ThemeStyle.Material3Expressive -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }
        else -> when (themeStyle) {
            ThemeStyle.LiquidGlass -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
            ThemeStyle.Material3Expressive -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    }

    val targetContentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 200),
        label = "zafiroChipBgColor",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(durationMillis = 200),
        label = "zafiroChipContentColor",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth)
            .clip(G2FieldShape(32.dp))
            .background(animatedBgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = animatedContentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}
