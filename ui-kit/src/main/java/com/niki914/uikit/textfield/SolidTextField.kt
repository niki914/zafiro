package com.niki914.uikit.textfield

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.motion.springSquish

@Composable
internal fun SolidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    moveCursorToEndOnFocus: Boolean = false,
    minHeight: Dp = 52.dp,
    contentVerticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    expandedLayout: Boolean = false,
    expandedActionsRow: (@Composable RowScope.() -> Unit)? = null,
    style: ZafiroTextFieldStyle = ZafiroTextFieldStyle.Default,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val shape = when {
        style == ZafiroTextFieldStyle.Composer -> {
            if (themeStyle == ThemeStyle.Material3Expressive) G2FieldShape(36.dp) else RoundedCornerShape(36.dp)
        }
        themeStyle == ThemeStyle.Material3Expressive -> G2FieldShape(28.dp)
        else -> RoundedCornerShape(28.dp)
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

    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }

    // 核心数据流同步：外部 value 被清空（例如发送完成后）时，必须同步清空内部文本与光标
    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val interactiveEffectsEnabled = enabled && (!isFocused || textFieldValue.text.isEmpty())
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
        BasicTextField(
            value = textFieldValue,
            onValueChange = { nextValue ->
                textFieldValue = nextValue
                if (nextValue.text != value) {
                    onValueChange(nextValue.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
            interactionSource = interactionSource,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = textColor),
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            onTextLayout = { layout -> onTextLayout?.invoke(layout) },
            decorationBox = { innerTextField ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = if (expandedLayout) Arrangement.Top else Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (expandedLayout) Modifier.padding(top = 12.dp) else Modifier),
                        verticalAlignment = contentVerticalAlignment,
                    ) {
                        if (leadingContent != null && !expandedLayout) {
                            CompositionLocalProvider(LocalContentColor provides trailingColor) {
                                leadingContent(this@Row)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 0.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (textFieldValue.text.isEmpty() && placeholder != null) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = placeholderColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        if (trailingContent != null && !expandedLayout) {
                            CompositionLocalProvider(LocalContentColor provides trailingColor) {
                                trailingContent(this@Row)
                            }
                        }
                    }
                    if (expandedLayout && (expandedActionsRow != null || leadingContent != null || trailingContent != null)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (expandedActionsRow != null) {
                                expandedActionsRow()
                            } else {
                                if (leadingContent != null) {
                                    CompositionLocalProvider(LocalContentColor provides trailingColor) {
                                        leadingContent(this@Row)
                                    }
                                }
                                if (trailingContent != null) {
                                    CompositionLocalProvider(LocalContentColor provides trailingColor) {
                                        trailingContent(this@Row)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
