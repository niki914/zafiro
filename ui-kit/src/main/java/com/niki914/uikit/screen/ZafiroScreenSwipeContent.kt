package com.niki914.uikit.screen

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.LiquidScreenSwipeContent
import com.niki914.uikit.infra.TitleDirection

@Composable
fun <T> ZafiroScreenSwipeContent(
    targetState: T,
    direction: TitleDirection,
    modifier: Modifier = Modifier,
    onTransitionActiveChanged: (Boolean) -> Unit = {},
    content: @Composable AnimatedContentScope.(targetState: T) -> Unit,
) = LiquidScreenSwipeContent(
    targetState = targetState,
    direction = direction,
    modifier = modifier,
    onTransitionActiveChanged = onTransitionActiveChanged,
    content = content,
)
