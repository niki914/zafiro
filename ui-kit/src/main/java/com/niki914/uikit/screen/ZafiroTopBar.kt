package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.screen.internal.ZafiroTopBarLayout

/**
 * Zafiro 统一顶栏门面组件（Layer 3 前景交互层）。
 *
 * 极简设计原则：
 * - 骨架铁律：两端三槽坐标系恒定，无论任何主题，标题均在对称安全区间内平滑过渡。
 * - 纯净分流：底层通过 [ZafiroTopBarLayout] 与 [ZafiroTopBarButton] 实现风格分流，
 *   在 LiquidGlass 下呈现毛玻璃浮空球与液态弹性，在 MD3/MD3E 下呈现 Google 原生无背景图标与微动效。
 */
@Composable
fun ZafiroTopBar(
    title: ZafiroScreenTitle,
    titleDirection: TitleDirection,
    baseTitleAlpha: Float,
    topInset: Dp,
    modifier: Modifier = Modifier,
    isOccluded: Boolean = false,
    leftButtonAlpha: Float = 1f,
    rightButtonAlpha: Float = 1f,
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    actionsEnabled: Boolean = true,
    backdrop: Backdrop? = null,
    titleHorizontalPadding: Dp = ZafiroTopBarDefaults.TitleHorizontalPadding,
    onLeftClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
    onLeftButtonBoundsChanged: ((Rect) -> Unit)? = null,
    onRightButtonBoundsChanged: ((Rect) -> Unit)? = null,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable () -> Unit)? = null,
) = ZafiroTopBarLayout(
    title = title,
    titleDirection = titleDirection,
    baseTitleAlpha = baseTitleAlpha,
    topInset = topInset,
    modifier = modifier,
    isOccluded = isOccluded,
    leftButtonAlpha = leftButtonAlpha,
    rightButtonAlpha = rightButtonAlpha,
    showLeftButton = showLeftButton,
    showRightButton = showRightButton,
    actionsEnabled = actionsEnabled,
    backdrop = backdrop,
    titleHorizontalPadding = titleHorizontalPadding,
    onLeftClick = onLeftClick,
    onRightClick = onRightClick,
    onLeftButtonBoundsChanged = onLeftButtonBoundsChanged,
    onRightButtonBoundsChanged = onRightButtonBoundsChanged,
    leftButton = leftButton,
    rightButton = rightButton,
    overlayContent = overlayContent,
)
