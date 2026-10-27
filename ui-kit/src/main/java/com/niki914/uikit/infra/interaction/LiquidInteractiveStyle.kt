package com.niki914.uikit.infra.interaction

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

val LiquidButtonInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 4.dp,
        maxDragScalePx = 4.dp,
    )

val ActionBarButtonInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 8.dp,
        maxDragScalePx = 0.dp,
    )
