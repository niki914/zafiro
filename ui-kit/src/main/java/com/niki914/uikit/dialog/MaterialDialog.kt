package com.niki914.uikit.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LocalLiquidDialogHostState
import com.niki914.uikit.infra.nextLiquidDialogHostEntryId
import com.niki914.uikit.infra.shape.G2FieldShape

@Composable
internal fun MaterialDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackgroundTap: Boolean = true,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val hostState = LocalLiquidDialogHostState.current
        ?: error("MaterialDialog must be used inside LiquidScreen")
    val dialogId = remember { nextLiquidDialogHostEntryId() }

    SideEffect {
        hostState.upsert(dialogId, visible) {
            MaterialDialogSurface(
                visible = visible,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                dismissOnBackgroundTap = dismissOnBackgroundTap,
                title = title,
                text = text,
                actions = actions,
                content = content,
            )
        }
    }

    DisposableEffect(hostState, dialogId) {
        onDispose {
            hostState.remove(dialogId)
        }
    }
}

@Composable
private fun MaterialDialogSurface(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackgroundTap: Boolean = true,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val scrimColor = colorScheme.scrim.copy(alpha = 0.32f)
    val panelShape = G2FieldShape(28.dp)

    var dialogMounted by remember { mutableStateOf(false) }
    val effectiveVisible = dialogMounted && visible
    LaunchedEffect(Unit) {
        withFrameNanos { }
        dialogMounted = true
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(effectiveVisible) {
        if (effectiveVisible) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = effectiveVisible) {
        onDismissRequest()
    }

    val scaleAnimationSpec: AnimationSpec<Float> = when (themeStyle) {
        ThemeStyle.Material3Expressive -> {
            if (effectiveVisible) {
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            } else {
                tween(durationMillis = 140, easing = FastOutLinearInEasing)
            }
        }
        else -> {
            if (effectiveVisible) {
                tween(durationMillis = 200, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 150, easing = FastOutLinearInEasing)
            }
        }
    }
    val targetScale = if (effectiveVisible) {
        1f
    } else {
        if (themeStyle == ThemeStyle.Material3Expressive) 0.88f else 0.90f
    }
    val panelScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = scaleAnimationSpec,
        label = "materialDialogPanelScale",
    )

    AnimatedVisibility(
        visible = effectiveVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 120, easing = LinearEasing)),
        exit = fadeOut(animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing)),
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val horizontalMargin = 24.dp
            val verticalMargin = 24.dp
            val maxPanelWidth = (maxWidth - horizontalMargin * 2)
                .coerceAtLeast(0.dp)
                .coerceAtMost(360.dp)
            val minPanelWidth = if (maxPanelWidth < 200.dp) {
                maxPanelWidth
            } else {
                200.dp
            }
            val maxPanelHeight = (maxHeight - verticalMargin * 2).coerceAtLeast(0.dp)
            val scrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .clickable(
                        enabled = true,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (dismissOnBackgroundTap) {
                                onDismissRequest()
                            }
                        },
                    ),
            )

            Surface(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = panelScale
                        scaleY = panelScale
                    }
                    .padding(horizontal = horizontalMargin, vertical = verticalMargin)
                    .widthIn(min = minPanelWidth, max = maxPanelWidth)
                    .heightIn(max = maxPanelHeight)
                    .clickable(
                        enabled = true,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = panelShape,
                color = colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    title?.invoke()
                    text?.invoke()
                    content?.invoke(this)
                    if (actions != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}
