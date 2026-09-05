package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    /** 内容行（leading/文本/trailing）的纵向对齐。Top 用于多行输入框：内容随高度增长锚定顶部。 */
    contentVerticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    /** 展开布局：leading/trailing 移到文本区下方独立行（文本行内不显示按钮）。 */
    expandedLayout: Boolean = false,
    /** 展开布局下按钮行：左 leading 右 trailing，与文本区同一玻璃容器内。 */
    expandedActionsRow: (@Composable RowScope.() -> Unit)? = null,
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
        minHeight = minHeight,
        contentVerticalAlignment = contentVerticalAlignment,
        onTextLayout = onTextLayout,
        expandedLayout = expandedLayout,
        expandedActionsRow = expandedActionsRow,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}
