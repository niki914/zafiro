package com.niki914.uikit.dialog

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.motion.springSquish

/**
 * 通用弹窗动作按钮：
 * - LiquidGlass 态：胶囊背景、平分或铺满整行宽度；
 * - Material3 / Material3Expressive 态：高对比实心底板（主操作为 Filled Button，次操作为 FilledTonal Button），靠右对齐；MD3E 模式叠加弹簧按压微反馈。
 */
@Composable
fun RowScope.ZafiroDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
) {
    val themeStyle = LocalThemeStyle.current

    when (themeStyle) {
        ThemeStyle.LiquidGlass -> {
            MaterialTintLiquidButton(
                text = text,
                onClick = onClick,
                modifier = modifier.weight(1f),
                enabled = enabled,
                containerColor = if (isPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (isPrimary) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            val interactionSource = remember { MutableInteractionSource() }
            val motionModifier = if (themeStyle == ThemeStyle.Material3Expressive && enabled) {
                Modifier.springSquish(interactionSource)
            } else {
                Modifier
            }

            if (isPrimary) {
                Button(
                    onClick = onClick,
                    modifier = modifier.then(motionModifier),
                    enabled = enabled,
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onClick,
                    modifier = modifier.then(motionModifier),
                    enabled = enabled,
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
