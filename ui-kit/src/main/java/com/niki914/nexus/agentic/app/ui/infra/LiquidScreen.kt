package com.niki914.nexus.agentic.app.ui.infra

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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay

@Composable
fun LiquidScreen(
    state: LiquidScreenState,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val density = LocalDensity.current
    val hazeState = rememberHazeState(blurEnabled = true)
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
        // Layer 1: page content owns the haze source placement.
        CompositionLocalProvider(
            LocalLiquidScreenContentContext provides LiquidScreenContentContext(
                topPadding = actionBarHeight,
                hazeState = hazeState,
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

        // Layer 2: action bar progressive blur background
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
                    .hazeEffect(state = hazeState) {
                        tints = listOf(
                            HazeTint(
                                if (isDarkTheme) Color.Black else Color.White
                            )
                        )
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f
                        )
                    }
            )
        }

        // Layer 3: action bar foreground
        Box(
            modifier = Modifier
                .zIndex(3f)
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(chromeHeight)
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
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
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
