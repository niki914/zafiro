package com.niki914.uikit.button

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.TintLiquidButton

/**
 * 通用主操作按钮门面（统一三模分流）：
 * - [ThemeStyle.LiquidGlass]: 胶囊毛玻璃折射与按压拖拽形变（[MaterialTintLiquidButton]）；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: M3 规范高对比实心按钮（[MaterialButton]，零 Backdrop 性能损耗，MD3E 叠加弹性弹簧反馈）。
 */
@Composable
fun ZafiroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    buttonHeight: Dp = 56.dp,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            MaterialTintLiquidButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                containerColor = containerColor,
                contentColor = contentColor,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                containerColor = containerColor,
                contentColor = contentColor,
                buttonHeight = buttonHeight,
                isLoading = isLoading,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
            )
        }
    }
}

/**
 * 通用次操作 / 色调按钮门面（统一三模分流）：
 * - [ThemeStyle.LiquidGlass]: 柔和毛玻璃底板与阻尼拖拽反馈（[TintLiquidButton]）；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: M3 规范色调按钮（[MaterialTonalButton]，MD3E 支持 Squircle 与弹性触觉）。
 */
@Composable
fun ZafiroTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lightContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    darkContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    buttonHeight: Dp = 56.dp,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            TintLiquidButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                lightContainerColor = lightContainerColor,
                darkContainerColor = darkContainerColor,
                contentColor = contentColor,
                buttonHeight = buttonHeight,
                isLoading = isLoading,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialTonalButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                lightContainerColor = lightContainerColor,
                darkContainerColor = darkContainerColor,
                contentColor = contentColor,
                buttonHeight = buttonHeight,
                isLoading = isLoading,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
            )
        }
    }
}
