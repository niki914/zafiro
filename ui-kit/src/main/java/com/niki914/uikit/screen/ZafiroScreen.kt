package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.nav.LocalNavigationEntry
import com.niki914.uikit.infra.nav.TitleBarScrollState

@Composable
fun ZafiroScreen(
    state: ZafiroScreenState,
    modifier: Modifier = Modifier,
    titleBarScroll: TitleBarScrollState = LocalNavigationEntry.current.titleBarScroll,
    actionsEnabled: Boolean = true,
    isOccluded: Boolean = false,
    leftButtonAlpha: Float = 1f,
    rightButtonAlpha: Float = 1f,
    showLeftButton: Boolean? = null,
    showRightButton: Boolean? = null,
    onLeftClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
    onLeftButtonBoundsChanged: ((Rect) -> Unit)? = null,
    onRightButtonBoundsChanged: ((Rect) -> Unit)? = null,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) = LiquidScreen(
    state = state,
    modifier = modifier,
    titleBarScroll = titleBarScroll,
    actionsEnabled = actionsEnabled,
    isOccluded = isOccluded,
    leftButtonAlpha = leftButtonAlpha,
    rightButtonAlpha = rightButtonAlpha,
    showLeftButton = showLeftButton,
    showRightButton = showRightButton,
    onLeftClick = onLeftClick,
    onRightClick = onRightClick,
    onLeftButtonBoundsChanged = onLeftButtonBoundsChanged,
    onRightButtonBoundsChanged = onRightButtonBoundsChanged,
    leftButton = leftButton,
    rightButton = rightButton,
    overlayContent = overlayContent,
    content = content,
)
