package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.helper.tapToOpenViewer
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.content.CommandPanelPadX
import com.niki914.zafiro.app.ui.content.CommandRowPadInner
import com.niki914.zafiro.app.ui.content.CommandRowPadOuter
import com.niki914.zafiro.app.ui.viewer.LocalViewerOpener
import com.niki914.zafiro.app.ui.viewer.ViewerContent

@Composable
internal fun CodeToolBody(
    ui: ActionBodyUi.Custom.Command,
    modifier: Modifier = Modifier,
) {
    val outerCorner = 16.dp
    val innerCorner = 2.dp
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    val topShape = remember(outerCorner, innerCorner) {
        G2FieldShape(
            topStart = outerCorner,
            topEnd = outerCorner,
            bottomEnd = innerCorner,
            bottomStart = innerCorner,
        )
    }
    val bottomShape = remember(innerCorner, outerCorner) {
        G2FieldShape(
            topStart = innerCorner,
            topEnd = innerCorner,
            bottomEnd = outerCorner,
            bottomStart = outerCorner,
        )
    }

    val openViewer = LocalViewerOpener.current
    val commandText = ui.copyText ?: ui.command
    val canOpenCommandViewer = !ui.isRunning && commandText.isNotBlank()

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 上段：命令行（单行文本占满可用宽度，垂直居中对齐复制按钮，超出尾部省略，整体裁剪 G2 圆角）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(topShape)
                    .background(background, topShape)
                    .tapToOpenViewer(enabled = canOpenCommandViewer) {
                        openViewer(ViewerContent.Text(commandText))
                    }
                    .padding(top = CommandRowPadOuter, bottom = CommandRowPadInner)
                    .padding(start = CommandPanelPadX, end = CopyBtnGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ui.command,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.width(CopyBtnGap))

                MiniCopyButton(
                    text = commandText,
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // 下段：控制台输出（垂直居中对齐复制按钮，超出尾部省略，整体裁剪 G2 圆角）
            val hasOutput = ui.output.isNotBlank()
            val runningPlaceholder = stringResource(R.string.ui_tool_status_running) + "..."
            val displayText = if (hasOutput) ui.output else if (ui.isRunning) runningPlaceholder else ""
            val canOpenOutputViewer = !ui.isRunning && hasOutput
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(bottomShape)
                    .background(background, bottomShape)
                    .tapToOpenViewer(enabled = canOpenOutputViewer) {
                        openViewer(ViewerContent.Text(ui.output))
                    }
                    .padding(top = CommandRowPadInner, bottom = CommandRowPadOuter)
                    .padding(start = CommandPanelPadX, end = CopyBtnGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        ui.isError -> MaterialTheme.colorScheme.error
                        !hasOutput && ui.isRunning -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (hasOutput) {
                    Spacer(modifier = Modifier.width(CopyBtnGap))
                    MiniCopyButton(
                        text = ui.output,
                    )
                }
            }
        }
    }
}
