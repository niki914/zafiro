package com.niki914.zafiro.app.ui.content.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val CONTEXT_RING_TRACK_ALPHA = 0.22f
internal val ContextRingStroke = 2.dp

/**
 * 在发送按钮边缘画一圈上下文占用环。
 *
 * 环的外围严格等于按钮的外围（外径 = 按钮直径）：Stroke 居中对齐向两侧各占 stroke / 2，
 * 故中心线直径取 [buttonDiameter] - stroke，使整个环紧贴在按钮轮廓外缘。
 */
internal fun DrawScope.drawContextRing(
    progress: Float,
    buttonDiameter: Dp,
    trackColor: Color,
    progressColor: Color,
) {
    val stroke = ContextRingStroke.toPx()
    val arcDiameter = buttonDiameter.toPx() - stroke
    val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
    val ringSize = Size(arcDiameter, arcDiameter)
    drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = ringSize,
        style = Stroke(width = stroke),
    )
    if (progress > 0f) {
        drawArc(
            color = progressColor,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * 使用 drawWithContent 在内容（按钮底板与图标）绘制之后叠加绘制上下文占用环，
 * 保证在 Material 3 实心底色或 Liquid 毛玻璃下，高对比度环均清晰可见且不被底盘遮挡。
 */
@Composable
internal fun Modifier.drawContextRingOverlay(
    progress: Float?,
    buttonDiameter: Dp,
): Modifier {
    if (progress == null) return this
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = CONTEXT_RING_TRACK_ALPHA)
    val progressColor = MaterialTheme.colorScheme.primary
    return this.drawWithContent {
        drawContent()
        drawContextRing(progress, buttonDiameter, trackColor, progressColor)
    }
}
