package com.niki914.nexus.agentic.app.ui.infra.shape

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.capsule.Continuity
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.continuities.G2Continuity

fun G2FieldShape(cornerRadius: Dp = 28.dp): Shape =
    G2CornerBasedShape(cornerRadius)

fun G2CardShape(cornerRadius: Dp = 28.dp): Shape =
    G2CornerBasedShape(cornerRadius)

fun G2BubbleShape(cornerRadius: Dp = 24.dp): Shape =
    G2CornerBasedShape(cornerRadius)

fun G2CapsuleShape(): Shape =
    ContinuousCapsule(G2Continuity())

private data class G2CornerBasedShape(
    private val topStartSize: CornerSize,
    private val topEndSize: CornerSize,
    private val bottomEndSize: CornerSize,
    private val bottomStartSize: CornerSize,
    private val continuity: Continuity = G2Continuity(),
) : CornerBasedShape(
    topStart = topStartSize,
    topEnd = topEndSize,
    bottomEnd = bottomEndSize,
    bottomStart = bottomStartSize,
) {

    constructor(
        cornerRadius: Dp,
        continuity: Continuity = G2Continuity(),
    ) : this(
        topStartSize = CornerSize(cornerRadius),
        topEndSize = CornerSize(cornerRadius),
        bottomEndSize = CornerSize(cornerRadius),
        bottomStartSize = CornerSize(cornerRadius),
        continuity = continuity,
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape =
        G2CornerBasedShape(
            topStartSize = topStart,
            topEndSize = topEnd,
            bottomEndSize = bottomEnd,
            bottomStartSize = bottomStart,
            continuity = continuity,
        )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val maxRadius = size.minDimension / 2f
        return continuity.createRoundedRectangleOutline(
            size = size,
            topLeft = topStart.fastCoerceAtMost(maxRadius),
            topRight = topEnd.fastCoerceAtMost(maxRadius),
            bottomRight = bottomEnd.fastCoerceAtMost(maxRadius),
            bottomLeft = bottomStart.fastCoerceAtMost(maxRadius),
        )
    }
}
