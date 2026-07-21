package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalLiquidViewportAvoidanceController =
    compositionLocalOf<LiquidViewportAvoidanceController?> { null }

@Stable
class LiquidViewportAvoidanceController {
    internal var activeRequest by mutableStateOf<LiquidViewportAvoidanceRequest?>(null)
        private set
    private var contentOffsetPx: Float = 0f

    fun request(
        id: Any,
        boundsInRoot: Rect,
        topMargin: Dp = 8.dp,
        bottomMargin: Dp = 12.dp,
    ) {
        val baseBoundsInRoot = Rect(
            left = boundsInRoot.left,
            top = boundsInRoot.top - contentOffsetPx,
            right = boundsInRoot.right,
            bottom = boundsInRoot.bottom - contentOffsetPx,
        )
        activeRequest = LiquidViewportAvoidanceRequest(
            id = id,
            boundsInRoot = baseBoundsInRoot,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
        )
    }

    fun release(id: Any) {
        if (activeRequest?.id === id) {
            activeRequest = null
        }
    }

    internal fun setContentOffsetPx(offsetPx: Float) {
        contentOffsetPx = offsetPx
    }
}

internal data class LiquidViewportAvoidanceRequest(
    val id: Any,
    val boundsInRoot: Rect,
    val topMargin: Dp,
    val bottomMargin: Dp,
)
