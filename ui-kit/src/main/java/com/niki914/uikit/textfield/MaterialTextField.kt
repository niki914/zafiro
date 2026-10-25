package com.niki914.uikit.textfield

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.motion.springSquish

/**
 * Material 输入框共享核心逻辑（状态双向同步、焦点、光标定位、动效计算与内部排版）：
 * 消除 Underline 与 Outlined 两大衍生变体的代码重复。
 */
@Composable
private fun MaterialTextFieldCore(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    moveCursorToEndOnFocus: Boolean = false,
    minHeight: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    container: @Composable (
        indicatorThickness: Dp,
        indicatorColor: Color,
        motionModifier: Modifier,
        innerContent: @Composable () -> Unit,
    ) -> Unit,
) {
    val themeStyle = LocalThemeStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val textColor = if (enabled) {
        colorScheme.onSurface
    } else {
        colorScheme.onSurface.copy(alpha = 0.38f)
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

    val indicatorThickness = if (isFocused) 2.dp else 1.dp
    val targetIndicatorColor = when {
        !enabled -> colorScheme.outline.copy(alpha = 0.38f)
        isFocused -> colorScheme.primary
        else -> colorScheme.outline
    }
    val indicatorColor by animateColorAsState(
        targetValue = targetIndicatorColor,
        animationSpec = tween(durationMillis = 150),
        label = "materialIndicatorColor",
    )

    container(indicatorThickness, indicatorColor, motionModifier) {
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
                .padding(contentPadding),
            interactionSource = interactionSource,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = textColor),
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            onTextLayout = { layout -> onTextLayout?.invoke(layout) },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leadingContent != null) {
                        CompositionLocalProvider(LocalContentColor provides trailingColor) {
                            leadingContent(this@Row)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (textFieldValue.text.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = placeholderColor,
                                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CompositionLocalProvider(LocalContentColor provides trailingColor) {
                            trailingContent(this@Row)
                        }
                    }
                }
            },
        )
    }
}

/**
 * Material 纯下划线输入框（专供 Dialog 等模态弹窗）：
 * - 纯透明底色，文字下方贴底指示下划线（未获焦 1dp outline，获焦 2dp primary）。
 * - 左右零多余边距，与弹窗标题及内容自然齐平。
 */
@Composable
internal fun MaterialUnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    moveCursorToEndOnFocus: Boolean = false,
    minHeight: Dp = 44.dp,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    MaterialTextFieldCore(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        minHeight = minHeight,
        contentPadding = PaddingValues(vertical = 8.dp),
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    ) { indicatorThickness, indicatorColor, motionModifier, innerContent ->
        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(motionModifier)
                .drawWithContent {
                    drawContent()
                    val stroke = indicatorThickness.toPx()
                    val y = size.height - stroke / 2f
                    drawLine(
                        color = indicatorColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                    )
                },
        ) {
            innerContent()
        }
    }
}

/**
 * Material 纯外边框输入框（专供 Settings 表单项与展开卡片）：
 * - 纯透明底色，四周连续圆角线框（未获焦 1dp outline，获焦 2dp primary）。
 * - 具有完整的内边距，工整圈定表单边界。
 */
@Composable
internal fun MaterialOutlinedTextField(
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = G2FieldShape(16.dp)
    MaterialTextFieldCore(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        minHeight = minHeight,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    ) { indicatorThickness, indicatorColor, motionModifier, innerContent ->
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .then(motionModifier),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(width = indicatorThickness, color = indicatorColor),
        ) {
            innerContent()
        }
    }
}
