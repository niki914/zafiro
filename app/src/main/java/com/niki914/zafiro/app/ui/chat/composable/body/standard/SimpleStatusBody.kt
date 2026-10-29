package com.niki914.zafiro.app.ui.chat.composable.body.standard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.helper.OutputBtnInset
import com.niki914.zafiro.app.ui.chat.helper.tapToOpenViewer
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.viewer.LocalViewerOpener
import com.niki914.zafiro.app.ui.viewer.ViewerContent

/**
 * 通用极简状态排版：
 * 在未开启“总是展示工具详细输出”且无简略输出时生效。
 * 遵循准则零：极简卡片处于运行中/成功/纯失败时，无须显示复制按钮，亦不开放查看器；
 * 仅当执行失败且有具体 reason 详情时，才在右上角提供复制按钮，并支持轻触进入查看器通读完整长报错。
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

    val openViewer = LocalViewerOpener.current
    val rawReason = remember(ui.reason) { ui.reason?.trim() }
    val isMultiline = rawReason?.contains('\n') == true
    // 准则零：仅失败且有具体报错详情时才展示复制按钮并允许打开全屏查看器
    val hasCopyableReason = ui.isFailed && !rawReason.isNullOrBlank()

    val text = if (hasCopyableReason) {
        val prefix = stringResource(R.string.ui_tool_status_failed)
        if (isMultiline) {
            "$prefix:\n$rawReason"
        } else {
            "$prefix: $rawReason"
        }
    } else {
        stringResource(if (ui.isFailed) R.string.ui_tool_status_failed else R.string.ui_tool_status_success)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .tapToOpenViewer(enabled = hasCopyableReason) {
                openViewer(ViewerContent.Text(rawReason.orEmpty()))
            },
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = if (hasCopyableReason) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (ui.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (hasCopyableReason) OutputBtnInset else 0.dp),
            )
        }

        if (hasCopyableReason) {
            MiniCopyButton(
                text = rawReason,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = CopyBtnGap, end = CopyBtnGap),
            )
        }
    }
}
