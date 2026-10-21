package com.niki914.uikit.scroll

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.scrollbar.LiquidAdaptiveScrollbar

@Composable
fun ZafiroAdaptiveScrollbar(
    state: ZafiroScrollbarState,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
) {
    LiquidAdaptiveScrollbar(
        state = state,
        modifier = modifier,
        topClearance = topClearance,
        bottomClearance = bottomClearance,
    )
}
