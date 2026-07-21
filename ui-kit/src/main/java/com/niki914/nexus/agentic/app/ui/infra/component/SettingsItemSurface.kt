package com.niki914.nexus.agentic.app.ui.infra.component

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    val isInteractive = enabled && currentOnClick != null

    val restingColor = Color.Transparent
    val pressedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    var backgroundColor by remember { mutableStateOf(restingColor) }
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
        Modifier.pointerInput(currentOnClick, hapticFeedbackType) {
            detectTapGestures(
                onPress = {
                    backgroundColor = pressedColor
                    try {
                        tryAwaitRelease()
                    } finally {
                        backgroundColor = restingColor
                    }
                },
                onTap = {
                    hapticFeedbackType?.let(haptics::performHapticFeedback)
                    currentOnClick?.invoke()
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
