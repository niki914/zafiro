package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnGap
import com.niki914.zafiro.app.ui.chat.helper.CopyBtnSize
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.helper.OutputBtnInset

/**
 * 助手 Markdown CodeBlock 增强组件：
 * - 采用 Markdown 官方 [MarkdownCodeBackground] 统一底板、无障碍语义与圆角裁剪；
 * - 显式绑定 [LocalMarkdownColors.current.text] 解决深色模式下正文未着色回退为纯黑的对比度缺陷；
 * - 提供右上角小号复制按钮；
 * - 防横滑穿模渐变严格与 CodeBlock 背景板颜色对齐，彻底消除黑斑。
 */
@Composable
internal fun AssistantCodeBlock(
    code: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val surfaceColor = MaterialTheme.colorScheme.surface
    // 真实合成底色：若 codeBackground 带 alpha，与当前窗口 surface 真实混合
    val actualBackgroundColor = remember(backgroundCodeColor, surfaceColor) {
        backgroundCodeColor.compositeOver(surfaceColor)
    }

    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val isNotBlank = remember(code) { code.isNotBlank() }
    val shape = RoundedCornerShape(codeBackgroundCornerSize)
    val textColor = style.color.takeOrElse { LocalMarkdownColors.current.text }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        showHeader = false,
        code = code,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = code,
                    color = textColor,
                    style = style.copy(
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Left,
                    ),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(codeBlockPadding)
                        .padding(end = if (isNotBlank) OutputBtnInset else 0.dp),
                )

                if (isNotBlank) {
                    // 右上角防穿模渐变：横向从 Transparent 过渡到真实合成底色，绝不使用带有黑色的高透明度脏斑
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .width(CopyBtnSize + 16.dp)
                            .height(CopyBtnSize + 8.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        actualBackgroundColor.copy(alpha = 0.85f),
                                        actualBackgroundColor,
                                    )
                                )
                            ),
                    )

                    MiniCopyButton(
                        text = code,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = CopyBtnGap, end = CopyBtnGap),
                    )
                }
            }
        }
    }
}
