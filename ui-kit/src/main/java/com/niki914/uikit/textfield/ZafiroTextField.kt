package com.niki914.uikit.textfield

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.component.LiquidTextField

/**
 * 通用下划线文本输入框（专供 Dialog 等模态弹窗）：
 * - 内部依据 [LocalThemeStyle] 纯粹分流：
 *   - [ThemeStyle.LiquidGlass]: 圆角毛玻璃槽（[LiquidGlassPresets.TextField]，内部自治 rememberLayerBackdrop 隔离）。
 *   - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 纯下划线（[MaterialUnderlineTextField]，全透明底色，文字下方贴底指示线）。
 */
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
    minHeight: Dp = 44.dp,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: ((TextLayoutResult?) -> Unit)? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
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
                visualTransformation = visualTransformation,
                onTextLayout = onTextLayout,
                backdrop = null,
                glassStyle = LiquidGlassPresets.TextField,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            )
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialUnderlineTextField(
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
                visualTransformation = visualTransformation,
                onTextLayout = onTextLayout,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            )
        }
    }
}
