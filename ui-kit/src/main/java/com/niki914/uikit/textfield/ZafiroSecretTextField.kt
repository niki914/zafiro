package com.niki914.uikit.textfield

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 密码/秘钥显隐通用输入框（专供 Settings 表单项如 API Key / Token）：
 * - 基于 [ZafiroOutlinedTextField] 构筑，尾部提供显隐切换眼睛操作。
 * - 自动继承双模规范（Liquid 圆角毛玻璃槽 vs Material 纯外边框）。
 */
@Composable
fun ZafiroSecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    toggleVisibleContentDescription: String,
    toggleHiddenContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    moveCursorToEndOnFocus: Boolean = false,
) {
    val contentDescription = if (visible) {
        toggleHiddenContentDescription
    } else {
        toggleVisibleContentDescription
    }

    ZafiroOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingContent = {
            SecretFieldVisibilityAction(
                visible = visible,
                onToggleVisibility = onToggleVisibility,
                contentDescription = contentDescription,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun RowScope.SecretFieldVisibilityAction(
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    contentDescription: String,
    enabled: Boolean,
) {
    IconButton(
        onClick = onToggleVisibility,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = contentDescription,
        )
    }
}
