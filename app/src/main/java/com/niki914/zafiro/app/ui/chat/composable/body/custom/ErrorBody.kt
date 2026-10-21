package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.composable.text.ToolResultText

/**
 * 错误信息正文排版（支持文本长按选中划词，右上角复制按钮提升至 Header 统一管理）。
 */
@Composable
internal fun ErrorDetailBody(
    ui: ActionBodyUi.Custom.ErrorDetail,
    modifier: Modifier = Modifier,
) {
    if (ui.body.isNullOrEmpty()) return

    Box(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            ToolResultText(
                text = ui.body,
                style = if (ui.isMonospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * 重试进行中提示排版。
 */
@Composable
internal fun RetryingBody(
    ui: ActionBodyUi.Custom.Retrying,
    modifier: Modifier = Modifier,
) {
    val count = stringResource(
        R.string.ui_home_retrying_attempt,
        ui.attempt,
        ui.maxAttempts,
    )
    val text = if (ui.reason.isBlank()) count else "$count: ${ui.reason.trim()}"
    Box(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            ToolResultText(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
