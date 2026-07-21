package com.niki914.nexus.agentic.app.ui.infra.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

fun GraphicsLayerScope.applyLiquidInteractiveTransform(
    style: LiquidInteractiveStyle,
    pressProgress: Float,
    offset: Offset,
    size: Size,
) {
    if (size.width <= 0f || size.height <= 0f || size.maxDimension <= 0f) {
        return
    }

    val width = size.width
    val height = size.height
    val scale = lerp(1f, 1f + style.pressScalePx.toPx() / height, pressProgress)

    val maxOffset = size.minDimension.coerceAtLeast(1f)
    translationX = maxOffset * tanh(style.translationDamping * offset.x / maxOffset)
    translationY = maxOffset * tanh(style.translationDamping * offset.y / maxOffset)

    val maxDragScale = style.maxDragScalePx.toPx() / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX =
        scale +
                maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                (width / height).fastCoerceAtMost(1f)
    scaleY =
        scale +
                maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                (height / width).fastCoerceAtMost(1f)
}
