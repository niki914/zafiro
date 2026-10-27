package com.niki914.zafiro.app.ui.content.composer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.motion.springSquish
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.composer.attach.ComposerAttachButton
import com.niki914.zafiro.app.ui.content.composer.send.ComposerSendButton

/** 紧凑态 composer 最小高度：12dp 容器垂直 padding × 2 + 48sp 按钮 footprint。 */
internal val COMPACT_COMPOSER_MIN_HEIGHT = 72.dp

/** 展开态编辑区最大行数。 */
private const val EXPANDED_MAX_LINES = 7

private val ComposerLiquidInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 3.dp,
        maxDragScalePx = 3.dp,
    )

/**
 * 主对话页面底部编辑输入框（Composer Dock 复合容器）：
 * - 自治容器：在 Liquid 下呈现毛玻璃大胶囊，在 Material 下呈现表面实体大胶囊。
 * - 内部文本输入采用无边框、无下划线、无多余背景的裸 [BasicTextField]。
 * - 自治维护单行紧凑与多行展开的按钮编排。
 */
@Composable
fun ZafiroChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    contextProgress: Float? = null,
    onAttachImageClick: () -> Unit = {},
) {
    var expandedLatch by remember { mutableStateOf(false) }
    var compactWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val hysteresisPx = with(density) { 4.dp.toPx() }
    val expanded = value.contains('\n') || expandedLatch

    val onLayout: (TextLayoutResult?) -> Unit = { layout ->
        if (layout != null) {
            if (!expandedLatch && layout.lineCount > 1) {
                expandedLatch = true
            } else if (expandedLatch && !value.contains('\n') && layout.lineCount == 1 && compactWidth > 0) {
                val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
                if (lineWidth <= compactWidth - hysteresisPx) {
                    expandedLatch = false
                }
            }
        }
    }

    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }

    LaunchedEffect(value) {
        if (value.isEmpty()) {
            expandedLatch = false
        }
        if (value != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    val themeStyle = LocalThemeStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = LocalAppDarkTheme.current
    val interactiveEffectsEnabled = !isFocused || textFieldValue.text.isEmpty()

    @Composable
    fun ComposerInputContent(contentModifier: Modifier = Modifier) {
        val textColor = colorScheme.onSurface
        val placeholderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

        BasicTextField(
            value = textFieldValue,
            onValueChange = { nextValue ->
                textFieldValue = nextValue
                if (nextValue.text != value) {
                    onValueChange(nextValue.text)
                }
            },
            modifier = contentModifier
                .fillMaxWidth()
                .heightIn(min = COMPACT_COMPOSER_MIN_HEIGHT)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                },
            singleLine = false,
            maxLines = if (expanded) EXPANDED_MAX_LINES else 1,
            textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = textColor),
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            onTextLayout = { layout -> onLayout(layout) },
            decorationBox = { innerTextField ->
                if (expanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 8.dp),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            innerTextField()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComposerAttachButton(onClick = onAttachImageClick)
                            ComposerSendButton(
                                isGenerating = isGenerating,
                                contextProgress = contextProgress,
                                onSendClick = onSendClick,
                                onStopClick = onStopClick,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ComposerAttachButton(
                            onClick = onAttachImageClick,
                            modifier = Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width - 6.dp.roundToPx(), placeable.height) {
                                    placeable.placeRelative(0, 0)
                                }
                            },
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 0.dp)
                                .onSizeChanged { size ->
                                    if (size.width > 0) {
                                        compactWidth = size.width
                                    }
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.ui_home_input_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = placeholderColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        ComposerSendButton(
                            isGenerating = isGenerating,
                            contextProgress = contextProgress,
                            onSendClick = onSendClick,
                            onStopClick = onStopClick,
                            modifier = Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val shrinkPx = 6.dp.roundToPx()
                                layout(placeable.width - shrinkPx, placeable.height) {
                                    placeable.placeRelative(-shrinkPx, 0)
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    when (themeStyle) {
        ThemeStyle.LiquidGlass -> {
            val fieldShape = G2FieldShape(36.dp)
            val glassStyle = LiquidGlassPresets.ComposerBar
            val resolvedBackdrop = LocalScreenBackdrop.current ?: rememberLayerBackdrop()
            val animationScope = rememberCoroutineScope()
            val interactiveHighlight = remember(animationScope) {
                InteractiveHighlight(animationScope = animationScope)
            }
            val surfaceColor = glassStyle.surfaceColor(isDarkTheme, colorScheme)
            val tintColor = colorScheme.primaryContainer.copy(alpha = 0.32f)

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .drawBackdrop(
                        backdrop = resolvedBackdrop,
                        shape = { fieldShape },
                        highlight = { glassStyle.highlight(isDarkTheme) },
                        shadow = { glassStyle.shadow(isDarkTheme) },
                        effects = { glassStyle.applyEffects(this, isDarkTheme) },
                        layerBlock = if (interactiveEffectsEnabled) {
                            {
                                applyLiquidInteractiveTransform(
                                    style = ComposerLiquidInteractiveStyle,
                                    pressProgress = interactiveHighlight.pressProgress,
                                    offset = interactiveHighlight.offset,
                                    size = size,
                                )
                            }
                        } else {
                            null
                        },
                        onDrawSurface = {
                            drawRect(tintColor, blendMode = BlendMode.Hue)
                            drawRect(surfaceColor)
                        },
                    )
                    .clip(fieldShape)
                    .then(
                        if (interactiveEffectsEnabled && ComposerLiquidInteractiveStyle.highlightEnabled) {
                            interactiveHighlight.modifier
                        } else {
                            Modifier
                        },
                    )
                    .then(if (interactiveEffectsEnabled) interactiveHighlight.gestureModifier else Modifier),
            ) {
                ComposerInputContent()
            }
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            val shape = G2FieldShape(36.dp)
            val interactionSource = remember { MutableInteractionSource() }
            val motionModifier = if (themeStyle == ThemeStyle.Material3Expressive && interactiveEffectsEnabled) {
                Modifier.springSquish(interactionSource, targetScale = 0.985f)
            } else {
                Modifier
            }

            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .then(motionModifier),
                shape = shape,
                color = colorScheme.surfaceContainerHigh,
            ) {
                ComposerInputContent()
            }
        }
    }
}
