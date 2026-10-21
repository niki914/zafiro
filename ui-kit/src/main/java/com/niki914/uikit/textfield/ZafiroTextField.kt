package com.niki914.uikit.textfield

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LiquidGlassStyle
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.component.LiquidTextField

enum class ZafiroTextFieldStyle {
    Default,
    Composer,
}

@Composable
fun ZafiroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
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
    backdrop: Backdrop? = LocalScreenBackdrop.current,
    style: ZafiroTextFieldStyle = ZafiroTextFieldStyle.Default,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val glassStyle = when (style) {
        ZafiroTextFieldStyle.Default -> LiquidGlassPresets.TextField
        ZafiroTextFieldStyle.Composer -> LiquidGlassPresets.ComposerBar
    }
    LiquidTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        minHeight = minHeight,
        contentVerticalAlignment = contentVerticalAlignment,
        onTextLayout = onTextLayout,
        expandedLayout = expandedLayout,
        expandedActionsRow = expandedActionsRow,
        backdrop = backdrop,
        glassStyle = glassStyle,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}
