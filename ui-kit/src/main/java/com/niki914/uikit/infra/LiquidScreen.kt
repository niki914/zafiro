package com.niki914.uikit.infra

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.internal.LiquidTopBarBackground
import com.niki914.uikit.screen.ZafiroTopBar
import com.niki914.uikit.screen.ZafiroTopBarDefaults
import com.niki914.uikit.screen.internal.MaterialTopBarBackground
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.nav.TitleBarScrollState

val LocalScreenBackdrop: ProvidableCompositionLocal<Backdrop?> =
    staticCompositionLocalOf { null }

/** 底部安全距离中，系统导航栏 inset 之外额外留出的设计间距。 */
private val BottomInsetSpacing = 50.dp

/** 吸底动作按钮距屏幕底边的设计间距。 */
private val ActionButtonBottomSpacing = 24.dp

/**
 * LiquidScreen 页面容器壳层：负责分层架构、滚动感知、毛玻璃背景与顶栏组装。
 *
 * 分层治理：
 * Layer 1 (zIndex 0) - Page content + nestedScroll
 * Layer 2 (zIndex 2) - Action bar background (毛玻璃 + surface 渐变底色)
 * Layer 3 (zIndex 3) - Action bar foreground (LiquidTopBar)
 * Layer 4 (zIndex 4) - LiquidDialogHost 浮层与弹窗
 */
@Composable
fun LiquidScreen(
    state: LiquidScreenState,
    titleBarScroll: TitleBarScrollState,
    modifier: Modifier = Modifier,
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
) {
    val density = LocalDensity.current
    val contentBackdrop = rememberLayerBackdrop()
    val chromeBackdrop = rememberLayerBackdrop()
    val screenBackdrop = rememberCombinedBackdrop(contentBackdrop, chromeBackdrop)
    val titleHorizontalPadding = with(density) { 12.dp * 2 + 48.sp.toDp() }
    val dialogHostState = remember { LiquidDialogHostState() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val buttonSlotHeight = ZafiroTopBarDefaults.ButtonSlotHeight
    val actionBarHeight = topInset + buttonSlotHeight
    val chromeHeight = topInset + buttonSlotHeight
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(density)
    val navigationBottom = with(density) { navigationBottomPx.toDp() }

    val collapseThreshold = if (state.isTitleCollapsible) TitleBarCollapseThreshold else 0.dp
    val collapseThresholdPx = with(density) { collapseThreshold.toPx() }
    val latestCollapseThresholdPx by rememberUpdatedState(collapseThresholdPx)

    val titleBarScrollConnection = remember(titleBarScroll) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val scrolledDownPx = -consumed.y
                if (scrolledDownPx != 0f) {
                    titleBarScroll.addScroll(scrolledDownPx, latestCollapseThresholdPx)
                }
                return Offset.Zero
            }
        }
    }
    val collapsed = titleBarScroll.collapsed

    val collapseAnimSpec = tween<Float>(durationMillis = 280, easing = LinearOutSlowInEasing)
    val barTarget = collapsed
    val titleTarget = if (state.isTitleCollapsible) collapsed else true

    val barAlpha by animateFloatAsState(
        targetValue = if (barTarget) 1f else 0f,
        animationSpec = collapseAnimSpec,
        label = "topBarAlpha",
    )
    val baseTitleAlpha by animateFloatAsState(
        targetValue = if (titleTarget) 1f else 0f,
        animationSpec = collapseAnimSpec,
        label = "topBarBaseTitleAlpha",
    )

    SideEffect {
        state.setActionBarHeight(actionBarHeight)
    }

    CompositionLocalProvider(
        LocalScreenBackdrop provides screenBackdrop,
        LocalLiquidDialogHostState provides dialogHostState,
        LocalHasActiveDialog provides dialogHostState.hasActiveDialog,
    ) {
        val themeStyle = LocalThemeStyle.current
        val isLiquid = themeStyle == ThemeStyle.LiquidGlass
        val backdropCaptureModifier = if (isLiquid) {
            Modifier.layerBackdrop(contentBackdrop)
        } else {
            Modifier
        }

        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            // Layer 1: page content
            CompositionLocalProvider(
                LocalLiquidScreenContentContext provides LiquidScreenContentContext(
                    topPadding = actionBarHeight,
                    bottomPadding = navigationBottom + BottomInsetSpacing,
                    actionButtonBottomPadding = navigationBottom + ActionButtonBottomSpacing,
                    isTitleCollapsible = state.isTitleCollapsible,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(titleBarScrollConnection)
                        .then(backdropCaptureModifier)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    content()
                }
            }

        // Layer 2: action bar background
        AnimatedVisibility(
            visible = state.showBlurLayer,
            modifier = Modifier
                .zIndex(2f)
                .align(Alignment.TopCenter),
            enter = fadeIn(tween(320, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(320, easing = FastOutSlowInEasing)),
        ) {
            if (isLiquid) {
                LiquidTopBarBackground(
                    contentBackdrop = contentBackdrop,
                    chromeBackdrop = chromeBackdrop,
                    chromeHeight = chromeHeight,
                    buttonSlotHeight = buttonSlotHeight,
                    topInset = topInset,
                    barAlpha = barAlpha,
                )
            } else {
                MaterialTopBarBackground(
                    chromeHeight = chromeHeight,
                    barAlpha = barAlpha,
                )
            }
        }


        // Layer 3: action bar foreground
        Box(
            modifier = Modifier
                .zIndex(3f)
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(chromeHeight),
        ) {
            val resolvedShowLeftButton = showLeftButton ?: state.showLeftButton
            val resolvedShowRightButton = showRightButton ?: state.showRightButton
            val resolvedOnLeftClick = onLeftClick ?: state.onLeftClick
            val resolvedOnRightClick = onRightClick ?: state.onRightClick

            ZafiroTopBar(
                title = state.title,
                titleDirection = state.titleDirection,
                baseTitleAlpha = baseTitleAlpha,
                topInset = topInset,
                isOccluded = isOccluded,
                leftButtonAlpha = leftButtonAlpha,
                rightButtonAlpha = rightButtonAlpha,
                showLeftButton = resolvedShowLeftButton,
                showRightButton = resolvedShowRightButton,
                actionsEnabled = actionsEnabled,
                backdrop = screenBackdrop,
                titleHorizontalPadding = titleHorizontalPadding,
                onLeftClick = resolvedOnLeftClick,
                onRightClick = resolvedOnRightClick,
                onLeftButtonBoundsChanged = onLeftButtonBoundsChanged,
                onRightButtonBoundsChanged = onRightButtonBoundsChanged,
                leftButton = leftButton,
                rightButton = rightButton,
                overlayContent = overlayContent,
            )
        }

        // Layer 4: dialog & popover host
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(4f),
        ) {
            dialogHostState.entries.forEach { entry ->
                key(entry.id) {
                    entry.content()
                }
            }
        }
    }
}
}
