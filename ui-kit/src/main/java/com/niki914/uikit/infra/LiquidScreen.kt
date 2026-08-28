package com.niki914.uikit.infra

import com.niki914.uikit.base.LocalAppDarkTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

@Composable
fun LiquidScreen(
    state: LiquidScreenState,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isDarkTheme = LocalAppDarkTheme.current
    val density = LocalDensity.current
    val chromeBackdrop = rememberLayerBackdrop()
    val dialogHostState = remember { LiquidDialogHostState() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val titleBarHeight = 56.dp
    val buttonSlotHeight = 72.dp
    val actionBarHeight = topInset + titleBarHeight
    val chromeHeight = topInset + buttonSlotHeight
    val activeAvoidanceRequest = state.viewportAvoidanceController.activeRequest
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(density)
    var screenHeightPx by remember { mutableStateOf(0) }

    // 背景板/小标题折叠状态唯一来源：当前导航条目的 titleCollapsed
    // （页面经 ReportTitleBarCollapsed 写入）。bar 拉取，无导航清零、
    // 无共享状态、无退场页竞争；条目存活期状态保留，返回时首帧恢复。

    // 动画时长与 action bar 左右按钮显隐动画一致，页面切换时两页滚动状态
    // 不同也不会闪变：alpha 总是从当前值动画到目标值。
    val collapseAnimSpec = tween<Float>(durationMillis = 280, easing = LinearOutSlowInEasing)
    // 背景与小标题共用同一信号源，保证两者同步动画；
    // 小标题仅在 Collapsible 页随折叠浮现，Pinned 页常驻。
    val barTarget = collapsed
    val titleTarget = if (state.isTitleCollapsible) collapsed else true
    val barAlpha by animateFloatAsState(
        targetValue = if (barTarget) 1f else 0f,
        animationSpec = collapseAnimSpec,
        label = "topBarAlpha",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleTarget) 1f else 0f,
        animationSpec = collapseAnimSpec,
        label = "topBarTitleAlpha",
    )
    val targetAvoidanceOffsetPx = with(density) {
        calculateLiquidViewportAvoidanceOffsetPx(
            screenHeightPx = screenHeightPx.toFloat(),
            topSafePx = actionBarHeight.toPx(),
            bottomBlockedInsetPx = maxOf(imeBottomPx, navigationBottomPx).toFloat(),
            request = activeAvoidanceRequest,
            topMarginPx = activeAvoidanceRequest?.topMargin?.toPx() ?: 0f,
            bottomMarginPx = activeAvoidanceRequest?.bottomMargin?.toPx() ?: 0f,
        )
    }
    val avoidanceOffsetPx by animateFloatAsState(
        targetValue = targetAvoidanceOffsetPx,
        animationSpec = tween(33, easing = FastOutSlowInEasing),
        label = "viewportAvoidanceOffset",
    )

    SideEffect {
        state.setActionBarHeight(actionBarHeight)
        state.viewportAvoidanceController.setContentOffsetPx(avoidanceOffsetPx)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size -> screenHeightPx = size.height },
    ) {
        // Layer 1: page content.
        CompositionLocalProvider(
            LocalLiquidScreenContentContext provides LiquidScreenContentContext(
                topPadding = actionBarHeight,
            ),
            LocalLiquidViewportAvoidanceController provides state.viewportAvoidanceController,
            LocalLiquidDialogHostState provides dialogHostState,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = avoidanceOffsetPx
                    },
            ) {
                content()
            }
        }

        // Layer 2: action bar background，颜色随内容滚动渐显。
        AnimatedVisibility(
            visible = state.showBlurLayer,
            modifier = Modifier
                .zIndex(2f)
                .align(Alignment.TopCenter),
            enter = fadeIn(tween(320, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(320, easing = FastOutSlowInEasing)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(chromeHeight)
                    .layerBackdrop(chromeBackdrop)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = barAlpha)
                    ),
            )
        }

        // Layer 3: action bar foreground
        Box(
            modifier = Modifier
                .zIndex(3f)
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(chromeHeight)
                // 吞掉顶栏区域的点按，防止穿透到下层内容（如滚动到顶栏下方的列表行）。
                // detectTapGestures 只消费 tap 不消费拖动，从顶栏起手的滚动仍能落到下层滚动容器。
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 4.dp),
        ) {
            // Title — always centered in the full bar width
            val buttonDuration = 280
            val titleDuration = 320
            var retainedLeftButton by remember { mutableStateOf(leftButton) }
            var retainedRightButton by remember { mutableStateOf(rightButton) }
            val displayedLeftButton = leftButton ?: retainedLeftButton
            val displayedRightButton = rightButton ?: retainedRightButton

            LaunchedEffect(leftButton, state.showLeftButton) {
                if (leftButton != null) {
                    retainedLeftButton = leftButton
                } else if (!state.showLeftButton) {
                    delay(buttonDuration.toLong())
                    if (!state.showLeftButton) {
                        retainedLeftButton = null
                    }
                }
            }

            LaunchedEffect(rightButton, state.showRightButton) {
                if (rightButton != null) {
                    retainedRightButton = rightButton
                } else if (!state.showRightButton) {
                    delay(buttonDuration.toLong())
                    if (!state.showRightButton) {
                        retainedRightButton = null
                    }
                }
            }

            AnimatedContent(
                targetState = state.title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .fillMaxHeight()
                    .padding(horizontal = 48.dp),
                transitionSpec = {
                    val titleEasing = FastOutSlowInEasing
                    val enterForward = slideInHorizontally(
                        animationSpec = tween(titleDuration, easing = titleEasing),
                        initialOffsetX = { fullWidth -> fullWidth }
                    ) + fadeIn(tween(titleDuration, easing = titleEasing))
                    val exitForward = slideOutHorizontally(
                        animationSpec = tween(titleDuration, easing = titleEasing),
                        targetOffsetX = { fullWidth -> -fullWidth }
                    ) + fadeOut(tween(titleDuration, easing = titleEasing))
                    val enterBack = slideInHorizontally(
                        animationSpec = tween(titleDuration, easing = titleEasing),
                        initialOffsetX = { fullWidth -> -fullWidth }
                    ) + fadeIn(tween(titleDuration, easing = titleEasing))
                    val exitBack = slideOutHorizontally(
                        animationSpec = tween(titleDuration, easing = titleEasing),
                        targetOffsetX = { fullWidth -> fullWidth }
                    ) + fadeOut(tween(titleDuration, easing = titleEasing))

                    when (state.titleDirection) {
                        TitleDirection.Forward -> enterForward togetherWith exitForward
                        TitleDirection.Back -> enterBack togetherWith exitBack
                        TitleDirection.None -> {
                            ContentTransform(
                                targetContentEnter = EnterTransition.None,
                                initialContentExit = ExitTransition.None,
                                sizeTransform = SizeTransform(clip = false),
                            )
                        }
                    }.using(SizeTransform(clip = false))
                },
                label = "title",
            ) { title ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(titleAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDarkTheme) Color.White else Color.Black
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            displayedLeftButton?.let { buttonContent ->
                // Left button
                val leftButtonScale = animateFloatAsState(
                    targetValue = if (state.showLeftButton) 1f else 0f,
                    animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    label = "leftButtonScale",
                )
                AnimatedVisibility(
                    visible = state.showLeftButton,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = topInset),
                    enter = fadeIn(tween(buttonDuration, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(buttonDuration, easing = LinearOutSlowInEasing)),
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            scaleX = leftButtonScale.value
                            scaleY = leftButtonScale.value
                        }
                    ) {
                        ActionBarButton(
                            onClick = { state.onLeftClick?.invoke() },
                            enabled = actionsEnabled,
                            backdrop = chromeBackdrop,
                            content = buttonContent,
                        )
                    }
                }
            }

            displayedRightButton?.let { buttonContent ->
                // Right button
                val rightButtonScale = animateFloatAsState(
                    targetValue = if (state.showRightButton) 1f else 0f,
                    animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    label = "rightButtonScale",
                )
                AnimatedVisibility(
                    visible = state.showRightButton,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = topInset),
                    enter = fadeIn(tween(buttonDuration, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(buttonDuration, easing = LinearOutSlowInEasing)),
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            scaleX = rightButtonScale.value
                            scaleY = rightButtonScale.value
                        }
                    ) {
                        ActionBarButton(
                            onClick = { state.onRightClick?.invoke() },
                            enabled = actionsEnabled,
                            backdrop = chromeBackdrop,
                            content = buttonContent,
                        )
                    }
                }
            }
        }

        // 第 4 层：Dialog host 必须高于顶栏按钮层，避免点击穿透。
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

private fun calculateLiquidViewportAvoidanceOffsetPx(
    screenHeightPx: Float,
    topSafePx: Float,
    bottomBlockedInsetPx: Float,
    request: LiquidViewportAvoidanceRequest?,
    topMarginPx: Float,
    bottomMarginPx: Float,
): Float {
    if (request == null || screenHeightPx <= 0f) {
        return 0f
    }

    val safeTopPx = topSafePx + topMarginPx
    val safeBottomPx = screenHeightPx - bottomBlockedInsetPx - bottomMarginPx
    if (safeBottomPx <= safeTopPx) {
        return 0f
    }

    return when {
        request.boundsInRoot.bottom > safeBottomPx -> safeBottomPx - request.boundsInRoot.bottom
        request.boundsInRoot.top < safeTopPx -> safeTopPx - request.boundsInRoot.top
        else -> 0f
    }
}
