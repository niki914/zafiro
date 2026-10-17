package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.zafiro.app.R
import kotlinx.coroutines.delay

/**
 * 单/多行输入框弹窗（沿用 [LiquidDialog] 形态）：弹出即聚焦。
 * 挂在 LiquidScreen 内容树内，返回键由弹窗接管。
 */
@Composable
fun InputLiquidDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    positiveButtonText: String,
    onPositiveClick: () -> Unit,
    hint: String? = null,
    positiveEnabled: Boolean = true,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    // 退出动画期间调用方已经把 value 清空，用最后一次可见的值把文字留住。
    var retainedValue by remember { mutableStateOf(value) }
    LaunchedEffect(visible, value) {
        if (visible) retainedValue = value
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    LiquidDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        content = {
            LiquidTextField(
                value = if (visible) value else retainedValue,
                onValueChange = onValueChange,
                placeholder = placeholder,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                contentVerticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            MaterialTintLiquidButton(
                text = positiveButtonText,
                onClick = onPositiveClick,
                enabled = positiveEnabled && enabled,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismissRequest,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}
