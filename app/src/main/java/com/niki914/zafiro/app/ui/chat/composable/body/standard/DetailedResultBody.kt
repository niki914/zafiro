package com.niki914.zafiro.app.ui.chat.composable.body.standard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.composable.text.ToolResultText
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.helper.OutputBtnInset
import com.niki914.zafiro.app.ui.chat.helper.tapToOpenViewer
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.composable.spacing.CommandPanelPadX
import com.niki914.zafiro.app.ui.chat.composable.spacing.OutputPadY
import com.niki914.zafiro.app.ui.viewer.LocalViewerOpener
import com.niki914.zafiro.app.ui.viewer.ViewerContent

/**
 * 通用详细结果排版：
 * 在开启“总是展示工具详细输出”时生效；面板底色 + 等宽结果文本。
 * 仅当 [ActionBodyUi.Standard.Detailed.showCopyButton] 为 true 时才在右上角提供复制按钮。
 * 右上角若做防穿模处理，背景严格与当前面板颜色对齐，杜绝黑斑。
 * 轻触面板可直接打开已美化格式的完整结果查看器，长按可在卡片内选词复制。
 */
@Composable
internal fun DetailedResultBody(
    ui: ActionBodyUi.Standard.Detailed,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
) {
    val openViewer = LocalViewerOpener.current
    val canOpenViewer = ui.text.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                containerColor,
                RoundedCornerShape(16.dp),
            )
            .tapToOpenViewer(enabled = canOpenViewer) {
                openViewer(ViewerContent.Text(ui.text))
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = CommandPanelPadX,
                    end = if (ui.showCopyButton) OutputBtnInset else CommandPanelPadX,
                )
                .padding(vertical = OutputPadY),
        ) {
            ToolResultText(
                text = ui.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (ui.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (ui.showCopyButton) {
            MiniCopyButton(
                text = ui.text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = CopyBtnGap, end = CopyBtnGap),
            )
        }
    }
}
