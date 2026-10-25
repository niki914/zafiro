package com.niki914.uikit.infra

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.screen.TitleDirection
import com.niki914.uikit.screen.internal.ZafiroTopBarLayout

/**
 * 顶栏前景组件（向下兼容入口，已委托至 [ZafiroTopBarLayout]）。
 */
@Composable
fun LiquidTopBar(
    title: LiquidScreenTitle,
    titleDirection: TitleDirection,
    baseTitleAlpha: Float,
    backdrop: Backdrop,
    topInset: Dp,
    titleHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
    isOccluded: Boolean = false,
    leftButtonAlpha: Float = 1f,
    rightButtonAlpha: Float = 1f,
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    actionsEnabled: Boolean = true,
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
