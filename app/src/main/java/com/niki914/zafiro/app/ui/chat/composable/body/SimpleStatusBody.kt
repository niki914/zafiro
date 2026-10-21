package com.niki914.zafiro.app.ui.chat.composable.body

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnSize
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.model.ToolPresentation

/**
 * 通用极简状态排版：
 * 在未开启“总是展示工具详细输出”且无简略输出时生效。
 * 呈现本地化「执行中 / 成功 / 失败」，失败为红色，失败有原因时附带展示原因（多行等宽并提供复制按钮）。
 */
@Composable
internal fun SimpleStatusBody(
    ui: ActionBodyUi.Standard.Simple,
    modifier: Modifier = Modifier,
) {
    if (ui.isRunning) {
        Text(
            text = stringResource(R.string.ui_tool_status_running),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val formattedReason = remember(ui.reason) { ToolPresentation.prettyPrintIfJson(ui.reason) }
    val isMultiline = formattedReason?.contains('\n') == true
    val hasCopyableReason = ui.isFailed && !formattedReason.isNullOrBlank()

    val text = if (hasCopyableReason) {
        val prefix = stringResource(R.string.ui_tool_status_failed)
        if (isMultiline) {
            "$prefix:\n$formattedReason"
        } else {
            "$prefix: $formattedReason"
        }
    } else {
        stringResource(if (ui.isFailed) R.string.ui_tool_status_failed else R.string.ui_tool_status_success)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text = text,
                style = if (isMultiline) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (ui.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (hasCopyableReason) {
            val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            Box(
                modifier = Modifier
                    .align(if (isMultiline) Alignment.TopEnd else Alignment.CenterEnd)
                    .width(CopyBtnSize + 16.dp)
                    .height(CopyBtnSize + 8.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                containerColor.copy(alpha = 0.85f),
                                containerColor,
                            )
                        )
                    ),
            )
            MiniCopyButton(
                text = formattedReason,
                modifier = Modifier
                    .align(if (isMultiline) Alignment.TopEnd else Alignment.CenterEnd)
                    .padding(end = CopyBtnGap),
            )
        }
    }
}
