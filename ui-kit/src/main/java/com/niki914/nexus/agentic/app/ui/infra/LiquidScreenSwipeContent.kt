package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun <T> LiquidScreenSwipeContent(
    targetState: T,
    direction: TitleDirection,
    modifier: Modifier = Modifier,
    onTransitionActiveChanged: (Boolean) -> Unit = {},
    content: @Composable AnimatedContentScope.(targetState: T) -> Unit,
) {
    val duration = 360
    val easing = FastOutSlowInEasing
    LaunchedEffect(targetState, direction) {
        if (direction == TitleDirection.None) {
            onTransitionActiveChanged(false)
            return@LaunchedEffect
        }
        onTransitionActiveChanged(true)
        delay(duration.toLong())
        onTransitionActiveChanged(false)
    }

    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val enterForward = slideInHorizontally(
                animationSpec = tween(duration, easing = easing),
                initialOffsetX = { fullWidth -> fullWidth }
            ) + fadeIn(animationSpec = tween(duration, easing = easing))
            val exitForward = slideOutHorizontally(
                animationSpec = tween(duration, easing = easing),
                targetOffsetX = { fullWidth -> -fullWidth }
            ) + fadeOut(animationSpec = tween(duration, easing = easing))
            val enterBack = slideInHorizontally(
                animationSpec = tween(duration, easing = easing),
                initialOffsetX = { fullWidth -> -fullWidth }
            ) + fadeIn(animationSpec = tween(duration, easing = easing))
            val exitBack = slideOutHorizontally(
                animationSpec = tween(duration, easing = easing),
                targetOffsetX = { fullWidth -> fullWidth }
            ) + fadeOut(animationSpec = tween(duration, easing = easing))

            when (direction) {
                TitleDirection.Forward -> enterForward togetherWith exitForward
                TitleDirection.Back -> enterBack togetherWith exitBack
                TitleDirection.None -> {
                    ContentTransform(
                        targetContentEnter = EnterTransition.None,
                        initialContentExit = ExitTransition.None,
                        sizeTransform = SizeTransform(clip = false),
                    )
                }
            }.using(SizeTransform(clip = false))
        },
        label = "liquid-screen-content",
        content = content,
    )
}
