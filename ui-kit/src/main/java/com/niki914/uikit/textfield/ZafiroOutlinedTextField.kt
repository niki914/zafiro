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
 * 通用外边框文本输入框（专供 Settings 表单项与展开卡片）：
 * - 内部依据 [LocalThemeStyle] 纯粹分流：
 *   - [ThemeStyle.LiquidGlass]: 圆角毛玻璃槽（[LiquidGlassPresets.TextField]，内部自治 rememberLayerBackdrop 隔离）。
 *   - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 纯外边框（[MaterialOutlinedTextField]，全透明底色，四周连续圆角线框）。
 */
@Composable
fun ZafiroOutlinedTextField(
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
            MaterialOutlinedTextField(
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
