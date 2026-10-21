package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LiquidTopBar

@Composable
fun ZafiroTopBar(
    title: ZafiroScreenTitle,
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
) = LiquidTopBar(
    title = title,
    titleDirection = titleDirection,
    baseTitleAlpha = baseTitleAlpha,
    isOccluded = isOccluded,
    leftButtonAlpha = leftButtonAlpha,
    rightButtonAlpha = rightButtonAlpha,
    showLeftButton = showLeftButton,
    showRightButton = showRightButton,
    actionsEnabled = actionsEnabled,
    onLeftClick = onLeftClick,
    onRightClick = onRightClick,
    onLeftButtonBoundsChanged = onLeftButtonBoundsChanged,
    onRightButtonBoundsChanged = onRightButtonBoundsChanged,
    backdrop = backdrop,
    topInset = topInset,
    titleHorizontalPadding = titleHorizontalPadding,
    modifier = modifier,
    leftButton = leftButton,
    rightButton = rightButton,
    overlayContent = overlayContent,
)
