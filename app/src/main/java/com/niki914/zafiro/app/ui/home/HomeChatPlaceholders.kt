package com.niki914.zafiro.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.zafiro.app.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeChatLoadingState(
    topPadding: Dp,
    bottomClearance: Dp,
    contentBackdropModifier: Modifier,
    onContentTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(contentBackdropModifier)
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = topPadding,
                bottom = bottomClearance,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onContentTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isExpressive) {
            LoadingIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeChatEmptyState(
    topPadding: Dp,
    bottomClearance: Dp,
    contentBackdropModifier: Modifier,
    onContentTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val state = remember {
        object {
            var maxImePx = 0
            var baseAngle = 0f
            var wasOpening = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(contentBackdropModifier)
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = topPadding,
                bottom = bottomClearance,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onContentTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            modifier = Modifier
                .requiredSize(240.dp)
                .graphicsLayer {
                    val currentPx = ime.getBottom(density)
                    val targetPx = imeTarget.getBottom(density)
                    if (targetPx > state.maxImePx) {
                        state.maxImePx = targetPx
                    }
                    if (currentPx > state.maxImePx) {
                        state.maxImePx = currentPx
                    }
                    if (currentPx == targetPx && targetPx > 0) {
                        state.maxImePx = targetPx
                    }

                    val max = state.maxImePx
                    val isOpening = targetPx > 0
                    val progress = if (max > 0) {
                        (currentPx.toFloat() / max).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    if (isOpening) {
                        state.wasOpening = true
                        rotationZ = state.baseAngle + progress * 360f
                    } else if (state.wasOpening) {
                        rotationZ = state.baseAngle + 360f + (1f - progress) * 360f
                        if (currentPx == 0 && targetPx == 0) {
                            state.baseAngle = (state.baseAngle + 720f) % 360f
                            state.wasOpening = false
                        }
                    } else {
                        rotationZ = state.baseAngle
                    }

                    translationY = -currentPx / 2f
                },
        )
    }
}
