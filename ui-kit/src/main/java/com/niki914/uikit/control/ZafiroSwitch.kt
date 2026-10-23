package com.niki914.uikit.control

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.LiquidToggle

/**
 * 通用开关控件门面组件：
 * - 遵循 Material 3 与 Liquid Glass 双模分流规范；
 * - 业务层直接面向本基件编程，通过 [LocalThemeStyle] 自动分流；
 * - [ThemeStyle.LiquidGlass]: 渲染为带毛玻璃折射与弹性状态机的 [LiquidToggle]；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 渲染为原生 [Switch]，零 GPU 离屏损耗。
 */
@Composable
fun ZafiroSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbContent: (@Composable () -> Unit)? = null,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidToggle(
                checked = checked,
                onCheckedChange = onCheckedChange ?: {},
                modifier = modifier,
                enabled = enabled,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = modifier,
                thumbContent = thumbContent,
                enabled = enabled,
                colors = colors,
                interactionSource = interactionSource,
            )
        }
    }
}
