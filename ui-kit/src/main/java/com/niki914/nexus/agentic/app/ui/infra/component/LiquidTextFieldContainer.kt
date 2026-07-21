package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.niki914.nexus.agentic.app.ui.infra.LocalLiquidViewportAvoidanceController
import com.niki914.nexus.agentic.app.ui.infra.interaction.InteractiveHighlight
import com.niki914.nexus.agentic.app.ui.infra.interaction.LiquidInteractiveStyle
import com.niki914.nexus.agentic.app.ui.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.nexus.agentic.app.ui.infra.shape.G2FieldShape

private val LiquidTextFieldInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 3.dp,
        maxDragScalePx = 3.dp,
    )

@Composable
internal fun LiquidTextFieldContainer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    moveCursorToEndOnFocus: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val backdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val viewportAvoidanceController = LocalLiquidViewportAvoidanceController.current
    val viewportAvoidanceRequestId = remember { Any() }
    var isFocused by remember { mutableStateOf(false) }
    var imeWasVisibleWhileFocused by remember { mutableStateOf(false) }
    var boundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }
    val interactiveEffectsEnabled = enabled && (!isFocused || textFieldValue.text.isEmpty())
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val fieldShape = G2FieldShape(28.dp)

    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    LaunchedEffect(isFocused, imeVisible) {
        syncLiquidTextFieldFocusWithImeVisibility(
            isFocused = isFocused,
            imeVisible = imeVisible,
            imeWasVisibleWhileFocused = imeWasVisibleWhileFocused,
            onImeVisibilityTracked = { imeWasVisibleWhileFocused = it },
            focusManager = focusManager,
        )
    }

    LaunchedEffect(
        enabled,
        isFocused,
        imeVisible,
        boundsInRoot,
        viewportAvoidanceController,
        viewportAvoidanceRequestId,
    ) {
        val currentBounds = boundsInRoot
        if (enabled && isFocused && imeVisible && currentBounds != null) {
            viewportAvoidanceController?.request(
                id = viewportAvoidanceRequestId,
                boundsInRoot = currentBounds,
            )
        } else {
            viewportAvoidanceController?.release(viewportAvoidanceRequestId)
        }
    }

    DisposableEffect(viewportAvoidanceController, viewportAvoidanceRequestId) {
        onDispose {
            viewportAvoidanceController?.release(viewportAvoidanceRequestId)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val textColor = if (enabled) {
        colorScheme.onSurface
    } else {
        colorScheme.onSurface.copy(alpha = 0.45f)
    }
    val placeholderColor = if (enabled) {
        colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    } else {
        colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    val trailingColor = if (enabled) {
        colorScheme.onSurfaceVariant
    } else {
        colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    val surfaceColor = if (enabled) {
        colorScheme.surfaceContainerHigh.copy(alpha = 0.64f)
    } else {
        colorScheme.surfaceContainer.copy(alpha = 0.42f)
    }
    val tintColor = if (enabled) {
        colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        Color.Transparent
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { nextValue ->
            textFieldValue = nextValue
            if (nextValue.text != value) {
                onValueChange(nextValue.text)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (moveCursorToEndOnFocus && focusState.isFocused) {
                    val textEnd = textFieldValue.text.length
                    if (textFieldValue.selection.start != textEnd ||
                        textFieldValue.selection.end != textEnd
                    ) {
                        textFieldValue = textFieldValue.copy(
                            selection = TextRange(textEnd),
                        )
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                boundsInRoot = coordinates.boundsInRoot()
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { fieldShape },
                effects = {
                    vibrancy()
                    blur(2.dp.toPx())
                    lens(12.dp.toPx(), 24.dp.toPx())
                },
                layerBlock = if (interactiveEffectsEnabled) {
                    {
                        applyLiquidInteractiveTransform(
                            style = LiquidTextFieldInteractiveStyle,
                            pressProgress = interactiveHighlight.pressProgress,
                            offset = interactiveHighlight.offset,
                            size = size,
                        )
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (enabled) {
                        drawRect(tintColor, blendMode = BlendMode.Hue)
                    }
                    drawRect(surfaceColor)
                },
            )
            .clip(fieldShape)
            .then(
                if (interactiveEffectsEnabled && LiquidTextFieldInteractiveStyle.highlightEnabled) {
                    interactiveHighlight.modifier
                } else {
                    Modifier
                },
            )
            .then(if (interactiveEffectsEnabled) interactiveHighlight.gestureModifier else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge.merge(
            TextStyle(color = textColor),
        ),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (textFieldValue.text.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = placeholderColor,
                        )
                    }
                    innerTextField()
                }

                if (trailingContent != null) {
                    CompositionLocalProvider(LocalContentColor provides trailingColor) {
                        trailingContent()
                    }
                }
            }
        },
    )
}

private fun syncLiquidTextFieldFocusWithImeVisibility(
    isFocused: Boolean,
    imeVisible: Boolean,
    imeWasVisibleWhileFocused: Boolean,
    onImeVisibilityTracked: (Boolean) -> Unit,
    focusManager: FocusManager,
) {
    if (!isFocused) {
        if (imeWasVisibleWhileFocused) {
            onImeVisibilityTracked(false)
        }
        return
    }
    if (imeVisible) {
        if (!imeWasVisibleWhileFocused) {
            onImeVisibilityTracked(true)
        }
        return
    }
    if (imeWasVisibleWhileFocused) {
        onImeVisibilityTracked(false)
        focusManager.clearFocus(force = true)
    }
}
