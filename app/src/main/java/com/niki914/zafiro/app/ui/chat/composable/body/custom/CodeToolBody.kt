package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnSize
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.helper.OutputBtnInset
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.content.CommandPanelPadX
import com.niki914.zafiro.app.ui.content.CommandRowPadY
import com.niki914.zafiro.app.ui.content.OutputPadY

@Composable
internal fun CodeToolBody(
    ui: ActionBodyUi.Custom.Command,
    modifier: Modifier = Modifier,
) {
    val outerCorner = 16.dp
    val innerCorner = 2.dp
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val actualBackground = remember(background, surfaceColor) {
        background.compositeOver(surfaceColor)
    }

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 上段：命令行（文本占满行，按钮垫主动渐变防遮挡）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        background,
                        G2FieldShape(
                            topStart = outerCorner,
                            topEnd = outerCorner,
                            bottomEnd = innerCorner,
                            bottomStart = innerCorner,
                        ),
                    )
                    .padding(vertical = CommandRowPadY),
            ) {
                Text(
                    text = ui.command,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CommandPanelPadX),
                )

                // 复制按钮与主动水平渐变底板
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(CopyBtnSize + 16.dp)
                        .height(CopyBtnSize + 8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    actualBackground.copy(alpha = 0.85f),
                                    actualBackground,
                                )
                            )
                        ),
                )
                MiniCopyButton(
                    text = ui.copyText ?: ui.command,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = CopyBtnGap),
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // 下段：控制台输出（遵循准则二/三，统一右上角 TopEnd 并通过内边距避让）
            val hasOutput = ui.output.isNotBlank()
            val runningPlaceholder = stringResource(R.string.ui_tool_status_running) + "..."
            val displayText = if (hasOutput) ui.output else if (ui.isRunning) runningPlaceholder else ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        background,
                        G2FieldShape(
                            topStart = innerCorner,
                            topEnd = innerCorner,
                            bottomEnd = outerCorner,
                            bottomStart = outerCorner,
                        ),
                    ),
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when {
                        ui.isError -> MaterialTheme.colorScheme.error
                        !hasOutput && ui.isRunning -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CommandPanelPadX, vertical = OutputPadY)
                        .padding(end = if (hasOutput) OutputBtnInset else 0.dp),
                )
                if (hasOutput) {
                    MiniCopyButton(
                        text = ui.output,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = CopyBtnGap, end = CopyBtnGap),
                    )
                }
            }
        }
    }
}
