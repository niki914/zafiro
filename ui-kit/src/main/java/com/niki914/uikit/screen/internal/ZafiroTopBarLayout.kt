package com.niki914.uikit.screen.internal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.button.ZafiroTopBarButton
import com.niki914.uikit.infra.LiquidScreenTitle
import com.niki914.uikit.screen.TitleDirection
import com.niki914.uikit.screen.ZafiroScreenTitle
import com.niki914.uikit.screen.ZafiroTopBarDefaults

/**
 * 顶栏共通骨架布局（三槽度量、转场动画、遮挡避让与显隐防抖动状态流）。
 *
 * 核心机制：
 * 1. 结构与渲染解耦：三槽骨架坐标恒定，两端保留对称 [titleHorizontalPadding] 安全间距。
 * 2. 动效风格化分流：
 *    - LiquidGlass：按钮显隐采用液态凝结与破裂动效（scale 0f + fade）；
 *    - Material 3 / MD3E：按钮显隐采用 Google 规范的平稳微位移淡入淡出，彻底告别气泡缩放。
 * 3. 按钮渲染委托：统一委托给 [ZafiroTopBarButton]，由其根据主题自适应渲染为毛玻璃浮空球或纯透明按钮。
 */
@Composable
internal fun ZafiroTopBarLayout(
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
) {
    val themeStyle = LocalThemeStyle.current
    val isLiquid = themeStyle == ThemeStyle.LiquidGlass

    val titleDuration = 320

    // 遮挡因子动画：当外部浮层（如右上角菜单或模型切换浮层）展开时淡出，收起时淡入
    // Material 模式下不隐藏标题，保留标准顶栏视觉并稳定锚点坐标
    val shouldOcclude = isLiquid && isOccluded
    val occludedAlpha by animateFloatAsState(
        targetValue = if (shouldOcclude) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "zafiroTopBarOccludedAlpha",
    )

    val effectiveTitleAlpha = baseTitleAlpha * occludedAlpha

    // 缓存最后一次非空按钮，确保退出动画进行期间按钮内容依然存在，消除瞬间白屏/闪烁
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

    // 按钮显隐转场规范：按风格分流
    val (leftEnterTransition, leftExitTransition) = remember(isLiquid) {
        if (isLiquid) {
            (fadeIn(tween(280, easing = LinearOutSlowInEasing)) +
                scaleIn(initialScale = 0f, animationSpec = tween(280, easing = LinearOutSlowInEasing))) to
            (fadeOut(tween(280, easing = LinearOutSlowInEasing)) +
                scaleOut(targetScale = 0f, animationSpec = tween(280, easing = LinearOutSlowInEasing)))
        } else {
            // Google Material 规范：平稳微位移淡入淡出，消除跳跃与气泡形变
            (fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 4 }) to
            (fadeOut(tween(150, easing = LinearOutSlowInEasing)) +
                slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { -it / 4 })
        }
    }

    val (rightEnterTransition, rightExitTransition) = remember(isLiquid) {
        if (isLiquid) {
            (fadeIn(tween(280, easing = LinearOutSlowInEasing)) +
                scaleIn(initialScale = 0f, animationSpec = tween(280, easing = LinearOutSlowInEasing))) to
            (fadeOut(tween(280, easing = LinearOutSlowInEasing)) +
                scaleOut(targetScale = 0f, animationSpec = tween(280, easing = LinearOutSlowInEasing)))
        } else {
            (fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 4 }) to
            (fadeOut(tween(150, easing = LinearOutSlowInEasing)) +
                slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { it / 4 })
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topInset + ZafiroTopBarDefaults.ButtonSlotHeight)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = ZafiroTopBarDefaults.HorizontalPadding),
    ) {
        // ── Center Slot: Title ────────────────────────────────────────────────
        val effectiveHorizontalPadding by animateDpAsState(
            targetValue = if (!isLeftButtonVisible && !isRightButtonVisible) {
                ZafiroTopBarDefaults.ExpandedContentPadding - ZafiroTopBarDefaults.HorizontalPadding
            } else {
                titleHorizontalPadding
            },
            animationSpec = tween(durationMillis = titleDuration, easing = FastOutSlowInEasing),
            label = "zafiroTopBarHorizontalPadding",
        )

        AnimatedContent(
            targetState = title,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topInset)
                .fillMaxHeight()
                .padding(horizontal = effectiveHorizontalPadding),
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
            label = "zafiroTitle",
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
                            color = MaterialTheme.colorScheme.onSurface,
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
                enter = leftEnterTransition,
                exit = leftExitTransition,
            ) {
                Box(modifier = Modifier.graphicsLayer { alpha = leftButtonAlpha }) {
                    ZafiroTopBarButton(
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
                enter = rightEnterTransition,
                exit = rightExitTransition,
            ) {
                Box(modifier = Modifier.graphicsLayer { alpha = rightButtonAlpha }) {
                    ZafiroTopBarButton(
                        onClick = { onRightClick?.invoke() },
                        enabled = actionsEnabled && isRightButtonVisible,
                        backdrop = backdrop,
                        onButtonBoundsChanged = onRightButtonBoundsChanged,
                        content = buttonContent,
                    )
                }
            }
        }

        // ── Overlay Slot: Context / Search Floating Content ───────────────────
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
