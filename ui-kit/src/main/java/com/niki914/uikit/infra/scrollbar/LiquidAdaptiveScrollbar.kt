package com.niki914.uikit.infra.scrollbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import kotlinx.coroutines.delay

/**
 * iOS 规范自适应微型滚动条视图（PRD 第 2、3、6 节规范）。
 *
 * @param state 滚动与超滚状态源
 * @param topClearance 顶部避让距离（避让状态栏及顶栏）
 * @param bottomClearance 底部避让距离（避让软键盘、底栏或吸底按钮）
 */
@Composable
fun LiquidAdaptiveScrollbar(
    state: LiquidScrollbarState,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val isDark = LocalAppDarkTheme.current
    val backdrop = rememberLayerBackdrop()

    val topClearancePx = with(density) { topClearance.toPx() }
    val bottomClearancePx = with(density) { bottomClearance.toPx() }
    val trackHeightPx = (state.viewportHeightPx - topClearancePx - bottomClearancePx).coerceAtLeast(0f)

    // 滑块几何计算
    val geometry = calculateThumbGeometry(
        viewportHeightPx = state.viewportHeightPx,
        contentHeightPx = state.contentHeightPx,
        scrollOffsetPx = state.scrollOffsetPx,
        maxScrollPx = state.maxScrollPx,
        overscrollOffsetPx = state.overscrollOffsetPx,
        trackHeightPx = trackHeightPx,
        density = density,
    )

    // 滑块是否正在被用户按住/拖拽（Scrubbing）
    var isThumbGrabbed by remember { mutableStateOf(false) }

    // 滑块物理长度平滑过渡（弹簧阻尼，杜绝异构 Item 出屏导致的高度瞬变跳动）
    val animatedLengthPx by animateFloatAsState(
        targetValue = geometry.lengthPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "thumbLength",
    )

    // 生命周期淡入淡出（PRD 第 6 节）
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(state.isScrollInProgress, state.overscrollOffsetPx != 0f, isThumbGrabbed, geometry.isVisible) {
        if (!geometry.isVisible) {
            alphaAnim.snapTo(0f)
            return@LaunchedEffect
        }
        if (state.isScrollInProgress || state.overscrollOffsetPx != 0f || isThumbGrabbed) {
            // 唤醒延迟 0ms，淡入 180ms
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
        } else {
            // 防抖静止维持 850ms
            delay(850L)
            // 平滑休眠淡出 280ms
            alphaAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            )
        }
    }

    // 拖拽宽度微变（PRD 第 2 节：按住滑块拖拽时才膨胀至 5.0dp，平时及 Overscroll 保持 3.5dp）
    val thumbWidth by animateDpAsState(
        targetValue = if (isThumbGrabbed) LiquidScrollbarDefaults.ActiveWidth else LiquidScrollbarDefaults.NormalWidth,
        animationSpec = tween(150),
        label = "thumbWidth",
    )

    // 主题自适应颜色（PRD 第 3 节）
    val thumbColor = if (isDark) {
        Color.White.copy(alpha = 0.42f)
    } else {
        Color.Black.copy(alpha = 0.38f)
    }

    if (alphaAnim.value > 0f && geometry.lengthPx > 0f) {
        // 触控热区外扩手势处理：仅在滚动条可见时启用手势拦截
        val hitSlopPx = with(density) { 24.dp.toPx() }
        val touchModifier = if (alphaAnim.value > 0.05f && geometry.isVisible && trackHeightPx > 0f) {
            Modifier.pointerInput(trackHeightPx, state.maxScrollPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val currentTop = geometry.topPx
                        val currentLen = geometry.lengthPx
                        if (offset.y in (currentTop - hitSlopPx)..(currentTop + currentLen + hitSlopPx)) {
                            isThumbGrabbed = true
                        } else {
                            // 点在轨道空白处：直接瞬移居中并进入抓取拖拽
                            val travelRange = (trackHeightPx - currentLen).coerceAtLeast(1f)
                            val targetTop = (offset.y - currentLen / 2f).coerceIn(0f, travelRange)
                            val progress = targetTop / travelRange
                            val targetScroll = progress * state.maxScrollPx
                            state.scrollBy(targetScroll - state.scrollOffsetPx)
                            isThumbGrabbed = true
                        }
                    },
                    onDragEnd = { isThumbGrabbed = false },
                    onDragCancel = { isThumbGrabbed = false },
                    onVerticalDrag = { change, dragAmount ->
                        if (isThumbGrabbed) {
                            change.consume()
                            val travelRange = (trackHeightPx - geometry.lengthPx).coerceAtLeast(1f)
                            val scrollRatio = dragAmount / travelRange
                            val scrollDelta = scrollRatio * state.maxScrollPx
                            state.scrollBy(scrollDelta)
                        }
                    },
                )
            }
        } else {
            Modifier
        }

        // 轨道容器：顶部与底部精确避让
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(
                    top = topClearance,
                    bottom = bottomClearance,
                    end = LiquidScrollbarDefaults.EndMargin,
                ),
            contentAlignment = Alignment.TopEnd,
        ) {
            // 右边缘透明手势拦截条（宽 32dp，外扩触控区域）
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight()
                    .then(touchModifier),
                contentAlignment = Alignment.TopEnd,
            ) {
                val thumbLengthDp = with(density) { animatedLengthPx.toDp() }
                val displayTopPx = if (state.overscrollOffsetPx < -0.5f) {
                    trackHeightPx - animatedLengthPx
                } else {
                    geometry.topPx
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = displayTopPx
                            alpha = alphaAnim.value
                        }
                        .width(thumbWidth)
                        .height(thumbLengthDp)
                        .layerBackdrop(backdrop)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(thumbColor),
                )
            }
        }
    }
}
