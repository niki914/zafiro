package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LiquidGlassStyle

@Composable
fun LiquidTextField(
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
    contentVerticalAlignment: Alignment.Vertical = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    backdrop: Backdrop? = null,
    glassStyle: LiquidGlassStyle = LiquidGlassPresets.TextField,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    LiquidTextFieldContainer(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        visualTransformation = visualTransformation,
        minHeight = minHeight,
        contentVerticalAlignment = contentVerticalAlignment,
        onTextLayout = onTextLayout,
        backdrop = backdrop,
        glassStyle = glassStyle,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}
