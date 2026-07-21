package com.niki914.nexus.agentic.app.ui.infra.interaction

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LiquidInteractiveStyle(
    val pressScalePx: Dp,
    val maxDragScalePx: Dp,
    val translationDamping: Float = 0.05f,
    val highlightEnabled: Boolean = true,
)

internal val LiquidButtonInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 4.dp,
        maxDragScalePx = 4.dp,
    )

internal val ActionBarButtonInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 24.dp,
        maxDragScalePx = 4.8.dp,
    )
