package com.niki914.uikit.infra.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SettingsItemSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 64.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    hapticFeedbackType: HapticFeedbackType? = HapticFeedbackType.ContextClick,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    onClick: (() -> Unit)? = null,
    /** 行内次级点击目标（如尾随「编辑」文字）的窗口坐标，命中时优先于 onClick。 */
    onTrailingActionClick: (() -> Unit)? = null,
    trailingActionBoundsInWindow: Rect? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnTrailingActionClick by rememberUpdatedState(onTrailingActionClick)
    val currentTrailingBounds by rememberUpdatedState(trailingActionBoundsInWindow)
    val isInteractive = enabled && (currentOnClick != null || currentOnTrailingActionClick != null)

    val restingColor = Color.Transparent
    val pressedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    var backgroundColor by remember { mutableStateOf(restingColor) }
    var surfaceOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    val animatedBackgroundColor by animateColorAsState(
        targetValue = if (isInteractive || (enabled && highlightPulseKey != null)) {
            backgroundColor
        } else {
            restingColor
        },
        animationSpec = tween(durationMillis = 500),
        label = "settingsItemSurfaceBackground",
    )

    LaunchedEffect(highlightPulseKey) {
        if (highlightPulseKey != null) {
            backgroundColor = pressedColor
            delay(highlightPulseDurationMillis.coerceAtLeast(0).toLong())
            backgroundColor = restingColor
        }
    }

    val interactiveModifier = if (isInteractive) {
        Modifier
            .onGloballyPositioned { surfaceOriginInWindow = it.positionInWindow() }
            .pointerInput(currentOnClick, currentOnTrailingActionClick, hapticFeedbackType) {
                detectTapGestures(
                    onPress = {
                        backgroundColor = pressedColor
                        try {
                            tryAwaitRelease()
                        } finally {
                            backgroundColor = restingColor
                        }
                    },
                    onTap = { offset ->
                        hapticFeedbackType?.let(haptics::performHapticFeedback)
                        val bounds = currentTrailingBounds
                        val trailing = currentOnTrailingActionClick
                        // 单一 pointerInput 内做命中分发，按压整行变色，不出现局部 ripple 割裂
                        if (trailing != null && bounds != null &&
                            bounds.contains(offset + surfaceOriginInWindow)
                        ) {
                            trailing()
                        } else {
                            currentOnClick?.invoke()
                        }
                    },
                )
            }
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(animatedBackgroundColor)
            .heightIn(min = minHeight)
            .then(interactiveModifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
