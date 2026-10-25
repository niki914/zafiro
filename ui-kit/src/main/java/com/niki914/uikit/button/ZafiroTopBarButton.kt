package com.niki914.uikit.button

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.ActionBarButton

/**
 * 顶栏专用动作按钮门面组件。
 *
 * 极简分流契约：
 * - LiquidGlass：分流至 [ActionBarButton]，呈现毛玻璃浮空球与动态高光
 * - Material3 / Material3Expressive：分流至 [MaterialTopBarButton]，呈现标准无底色透明按钮与通透 Ripple
 */
@Composable
fun ZafiroTopBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    onButtonBoundsChanged: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> ActionBarButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            backdrop = backdrop ?: emptyBackdrop(),
            onButtonBoundsChanged = onButtonBoundsChanged,
            content = content,
        )

        ThemeStyle.Material3, ThemeStyle.Material3Expressive -> MaterialTopBarButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            onButtonBoundsChanged = onButtonBoundsChanged,
            content = content,
        )
    }
}
