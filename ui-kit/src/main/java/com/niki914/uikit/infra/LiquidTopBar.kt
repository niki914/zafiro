package com.niki914.uikit.infra

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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme

/**
 * 顶栏前景组件：基于插槽解耦与三大正交因子（滚动、遮挡、覆盖）驱动。
 *
 * 核心职责：
 * 1. 基础三槽（Leading 左按钮、Center 居中标题、Trailing 右按钮）布局与几何测量。
 * 2. 遮挡避让机制：当外部弹层展开（[isOccluded] = true）时，居中标题通过动画平滑淡出至 0f，
 *    解除遮挡后平滑恢复至基础滚动透明度 [baseTitleAlpha]，避免与右侧扩展弹层物理空间重叠。
 * 3. 跨页导航滑动位移与同页就地瞬切解耦。
 */
@Composable
fun LiquidTopBar(
    title: LiquidScreenTitle,
    titleDirection: TitleDirection,
    baseTitleAlpha: Float,
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
    backdrop: Backdrop,
    topInset: Dp,
    titleHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable () -> Unit)? = null,
) {
    val isDarkTheme = LocalAppDarkTheme.current
    val buttonDuration = 280
    val titleDuration = 320

    // 遮挡因子动画：当外部浮层（如右上角 Popover）展开时淡出，收起时淡入
    val occludedAlpha by animateFloatAsState(
        targetValue = if (isOccluded) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "topBarOccludedAlpha",
    )

    // 最终标题透明度：基础滚动透明度 × 遮挡避让因子
    val effectiveTitleAlpha = baseTitleAlpha * occludedAlpha

    var lastNonNullLeftButton by remember { mutableStateOf(leftButton) }
    if (leftButton != null) {
        lastNonNullLeftButton = leftButton
    }
    val displayedLeftButton = leftButton ?: lastNonNullLeftButton
    val isLeftButtonVisible = showLeftButton && leftButton != null

    var lastNonNullRightButton by remember { mutableStateOf(rightButton) }
    if (rightButton != null) {
        lastNonNullRightButton = rightButton
    }
    val displayedRightButton = rightButton ?: lastNonNullRightButton
    val isRightButtonVisible = showRightButton && rightButton != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 4.dp),
    ) {
        // ── Center Slot: Title ────────────────────────────────────────────────
        AnimatedContent(
            targetState = title,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topInset)
                .fillMaxHeight()
                .padding(horizontal = titleHorizontalPadding),
            contentKey = { it.key ?: it },
            transitionSpec = {
                val titleEasing = FastOutSlowInEasing
                val enterForward = slideInHorizontally(
                    animationSpec = tween(titleDuration, easing = titleEasing),
                    initialOffsetX = { fullWidth -> fullWidth },
                ) + fadeIn(tween(titleDuration, easing = titleEasing))
                val exitForward = slideOutHorizontally(
                    animationSpec = tween(titleDuration, easing = titleEasing),
                    targetOffsetX = { fullWidth -> -fullWidth },
                ) + fadeOut(tween(titleDuration, easing = titleEasing))
                val enterBack = slideInHorizontally(
                    animationSpec = tween(titleDuration, easing = titleEasing),
                    initialOffsetX = { fullWidth -> -fullWidth },
                ) + fadeIn(tween(titleDuration, easing = titleEasing))
                val exitBack = slideOutHorizontally(
                    animationSpec = tween(titleDuration, easing = titleEasing),
                    targetOffsetX = { fullWidth -> fullWidth },
                ) + fadeOut(tween(titleDuration, easing = titleEasing))

                when (titleDirection) {
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
        ) { targetTitle ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = effectiveTitleAlpha },
                contentAlignment = Alignment.Center,
            ) {
                when (targetTitle) {
                    is LiquidScreenTitle.Text -> Text(
                        text = targetTitle.value,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDarkTheme) Color.White else Color.Black,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    is LiquidScreenTitle.Content -> targetTitle.content()
                }
            }
        }

        // ── Leading Slot: Left Button ─────────────────────────────────────────
        displayedLeftButton?.let { buttonContent ->
            AnimatedVisibility(
                visible = isLeftButtonVisible,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = topInset),
                enter = fadeIn(tween(buttonDuration, easing = LinearOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0f,
                        animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    ),
                exit = fadeOut(tween(buttonDuration, easing = LinearOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0f,
                        animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    ),
            ) {
                Box(
                    Modifier.graphicsLayer {
                        alpha = leftButtonAlpha
                    },
                ) {
                    ActionBarButton(
                        onClick = { onLeftClick?.invoke() },
                        enabled = actionsEnabled && isLeftButtonVisible,
                        backdrop = backdrop,
                        onButtonBoundsChanged = onLeftButtonBoundsChanged,
                        content = buttonContent,
                    )
                }
            }
        }

        // ── Trailing Slot: Right Button ────────────────────────────────────────
        displayedRightButton?.let { buttonContent ->
            AnimatedVisibility(
                visible = isRightButtonVisible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topInset),
                enter = fadeIn(tween(buttonDuration, easing = LinearOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0f,
                        animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    ),
                exit = fadeOut(tween(buttonDuration, easing = LinearOutSlowInEasing)) +
                    scaleOut(
                        targetScale = 0f,
                        animationSpec = tween(buttonDuration, easing = LinearOutSlowInEasing),
                    ),
            ) {
                Box(
                    Modifier.graphicsLayer {
                        alpha = rightButtonAlpha
                    },
                ) {
                    ActionBarButton(
                        onClick = { onRightClick?.invoke() },
                        enabled = actionsEnabled && isRightButtonVisible,
                        backdrop = backdrop,
                        onButtonBoundsChanged = onRightButtonBoundsChanged,
                        content = buttonContent,
                    )
                }
            }
        }

        // ── Overlay Slot: Future Expanded/Search Slot ─────────────────────────
        overlayContent?.let { overlay ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                overlay()
            }
        }
    }
}
