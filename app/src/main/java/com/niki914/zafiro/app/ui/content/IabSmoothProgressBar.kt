package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * r16 点 12：平滑加载进度条（两个 IAB 宿主：设置页 + 聊天浮窗共用）。
 *
 * 语义：
 * - [progress] 0..100 的离散百分比；[busy] = 导航未结束（意图层仍在轮询）；
 *   [loadToken] 每次新导航 +1——变了一定先 snap 回 0 再走动画，
 *   否则上一次加载的残帧会在新页面加载头几秒里"倒着走"。
 * - 上升用慢出曲线（420ms），回落/清零用快出曲线（160ms）：
 *   前进时"追上"进度，回退时迅速贴齐，视觉上始终是平滑追赶而非跳变。
 * - busy 且进度 <8% 时叠加一段 950ms 无限循环的扫掠高亮（"indeterminate" 提示），
 *   进度条不是死条。
 * - 完成（progress>=100 且非 busy）：先补到 100%、停留 120ms、淡出 180ms、再 snap 归零，
 *   宿主侧不需要自己管理可见性。
 */
@Composable
fun IabSmoothProgressBar(
    progress: Int,
    busy: Boolean,
    loadToken: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    barHeight: Dp = 3.dp,
) {
    val animatedProgress = remember { Animatable(0f) }
    val initialVisible = busy || (progress > 0 && progress < 100)
    var visible by remember { mutableStateOf(initialVisible) }
    var lastToken by remember { mutableStateOf(loadToken) }

    LaunchedEffect(loadToken, progress, busy) {
        if (loadToken != lastToken) {
            lastToken = loadToken
            animatedProgress.snapTo(0f)
            visible = true
        }

        val target = progress.coerceIn(0, 100) / 100f

        if (target >= animatedProgress.value) {
            animatedProgress.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 420, easing = LinearOutSlowInEasing),
            )
        } else {
            animatedProgress.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
            )
        }

        when {
            busy -> {
                visible = true
            }

            progress >= 100 -> {
                if (animatedProgress.value < 1f) {
                    animatedProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 140, easing = LinearOutSlowInEasing),
                    )
                }
                delay(120L)
                visible = false
                delay(220L)
                animatedProgress.snapTo(0f)
            }

            progress <= 0 -> {
                visible = false
                delay(120L)
                animatedProgress.snapTo(0f)
            }

            else -> {
                visible = true
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(durationMillis = 120, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(durationMillis = 180, easing = FastOutLinearInEasing)),
    ) {
        val infiniteTransition = rememberInfiniteTransition()
        val sweep by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )

        val fraction = animatedProgress.value.coerceIn(0f, 1f)
        val showSweep = busy && fraction < 0.08f

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val radius = size.height / 2f
            val minWidth = size.height.coerceAtMost(size.width)

            drawRoundRect(
                color = trackColor,
                cornerRadius = CornerRadius(radius, radius),
                size = Size(size.width, size.height),
            )

            if (fraction > 0f) {
                val filledWidth = (size.width * fraction).coerceAtLeast(minWidth)
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(radius, radius),
                    size = Size(filledWidth, size.height),
                )
            }

            if (showSweep) {
                val segmentWidth = size.width * 0.34f
                val startX = -segmentWidth + (size.width + segmentWidth) * sweep
                drawRoundRect(
                    color = color.copy(alpha = 0.42f),
                    topLeft = Offset(startX, 0f),
                    cornerRadius = CornerRadius(radius, radius),
                    size = Size(segmentWidth, size.height),
                )
            }
        }
    }
}
