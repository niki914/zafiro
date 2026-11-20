package com.niki914.uikit.ambient

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 顶部锚定的非线性渐隐流体光斑背景层。
 *
 * - 坐标系严格锁死在容器顶部 `[0, fadeHeight]`（固定物理高度），容器因多行输入或扩展槽增高时，
 *   顶部光斑像素分布不发生拉伸跳变。
 * - 使用 `BlendMode.DstIn` 非线性三次缓出蒙版：到达 `fadeHeight` 时光斑透明度硬截止至 0%，
 *   下方 100% 回归宿主的 Material3 / LiquidGlass 原色。
 * - 支持通过 [animate] 暂停帧循环（如被全屏悬浮窗遮挡时），恢复时从原相位无缝续播。
 */
@Composable
fun TopFadeAuroraOverlay(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    fadeHeight: Dp = 112.dp,
    maxAlpha: Float = 0.86f,
    animate: Boolean = true,
) {
    if (colors.isEmpty()) return

    val c0 by animateColorAsState(
        targetValue = colors[0],
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS),
        label = "aurora_c0",
    )
    val c1 by animateColorAsState(
        targetValue = colors.getOrElse(1) { colors[0] },
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS),
        label = "aurora_c1",
    )
    val c2 by animateColorAsState(
        targetValue = colors.getOrElse(2) { colors[0] },
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS),
        label = "aurora_c2",
    )
    val c3 by animateColorAsState(
        targetValue = colors.getOrElse(3) { colors.getOrElse(1) { colors[0] } },
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS),
        label = "aurora_c3",
    )

    // 互质周期驱动三组相位，避免光斑轨迹出现机械循环感；暂停时停帧并保留当前相位
    var phaseA by remember { mutableFloatStateOf(0f) }
    var phaseB by remember { mutableFloatStateOf(0f) }
    var phaseC by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaMs = ((frameNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, 100f)
                    phaseA = (phaseA + deltaMs * (TWO_PI / 4_200f)) % TWO_PI
                    phaseB = (phaseB + deltaMs * (TWO_PI / 6_100f)) % TWO_PI
                    phaseC = (phaseC + deltaMs * (TWO_PI / 7_900f)) % TWO_PI
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    Box(
        modifier = modifier.drawWithCache {
            val fadePx = fadeHeight.toPx().coerceAtLeast(1f)
            val layerPaint = Paint()
            val fadeMaskBrush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = maxAlpha),
                0.25f to Color.White.copy(alpha = maxAlpha * 0.88f),
                0.50f to Color.White.copy(alpha = maxAlpha * 0.55f),
                0.75f to Color.White.copy(alpha = maxAlpha * 0.18f),
                0.89f to Color.White.copy(alpha = maxAlpha * 0.05f),
                1.00f to Color.Transparent,
                startY = 0f,
                endY = fadePx,
            )

            onDrawBehind {
                val width = size.width
                if (width <= 0f || size.height <= 0f) return@onDrawBehind
                val clipHeight = fadePx.coerceAtMost(size.height)

                clipRect(left = 0f, top = 0f, right = width, bottom = clipHeight) {
                    drawIntoCanvas { canvas ->
                        val bounds = Rect(0f, 0f, width, clipHeight)
                        canvas.saveLayer(bounds, layerPaint)

                        // 柔和基底色，防止光斑游离时局部漏空
                        drawRect(
                            color = c0.copy(alpha = 0.62f),
                            size = Size(width, fadePx),
                        )

                        // 三个柔光球沿顶部固定坐标系做椭圆漂移与轻度呼吸缩放
                        val radius1 = width * (0.60f * (1f + 0.12f * sin(phaseC))) 
                        val center1 = Offset(
                            x = width * (0.26f + 0.25f * cos(phaseA)),
                            y = fadePx * (0.26f + 0.24f * sin(phaseA)),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(c1.copy(alpha = 0.90f), Color.Transparent),
                                center = center1,
                                radius = radius1,
                            ),
                            radius = radius1,
                            center = center1,
                        )

                        val radius2 = width * (0.56f * (1f + 0.12f * cos(phaseA + 0.8f)))
                        val center2 = Offset(
                            x = width * (0.74f + 0.24f * cos(phaseB + 1.2f)),
                            y = fadePx * (0.32f + 0.26f * sin(phaseB + 0.6f)),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(c2.copy(alpha = 0.85f), Color.Transparent),
                                center = center2,
                                radius = radius2,
                            ),
                            radius = radius2,
                            center = center2,
                        )

                        val radius3 = width * (0.52f * (1f + 0.12f * sin(phaseB + 1.7f)))
                        val center3 = Offset(
                            x = width * (0.50f + 0.30f * sin(phaseC)),
                            y = fadePx * (0.18f + 0.22f * cos(phaseC)),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(c3.copy(alpha = 0.78f), Color.Transparent),
                                center = center3,
                                radius = radius3,
                            ),
                            radius = radius3,
                            center = center3,
                        )

                        // 非线性渐隐硬截止蒙版
                        drawRect(
                            brush = fadeMaskBrush,
                            size = Size(width, fadePx),
                            blendMode = BlendMode.DstIn,
                        )

                        canvas.restore()
                    }
                }
            }
        },
    )
}

private const val COLOR_TRANSITION_MS = 650
private const val TWO_PI = (2.0 * PI).toFloat()
