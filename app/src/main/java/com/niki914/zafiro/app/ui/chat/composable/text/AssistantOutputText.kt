package com.niki914.zafiro.app.ui.chat.composable.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.text.BidiSelectionContainer
import com.niki914.uikit.text.BidiText
import com.niki914.zafiro.app.ui.chat.composable.body.custom.AssistantCodeBlock
import org.intellij.markdown.MarkdownTokenTypes

val LocalMarkdownScale = compositionLocalOf { 1.0f }

/** 预览样张：同一句 hello 的多文字变体 + 字符表 + 符号，只做字形展示，不作阅读内容。 */
internal const val AssistantMarkdownPreviewText = """
# hello

HELLO 你好 こんにちは ПРИВЕТ HOLA 안녕하세요 ALLÔ CIAO

## ＡＢＣ あいう 日月山川

- ABCDEFGHIJKLMNOPQRSTUVWXYZ
- abcdefghijklmnopqrstuvwxyz
- 0123456789 ! ? . , : ; — … · « » 「 」 『 』
- あいうえお かきくけこ さしすせそ
- 天地玄黄 宇宙洪荒 日月盈昃 辰宿列张

### ▲ ● ■ ◆ ★ ♪ ⚡ ⌘

| A | あ | 永 |
| --- | --- | --- |
| B | か | 空 |
| Γ | さ | 海 |

> ! ? ¿ ¡ : ; ~ ≈ ≠ ∞ °

`Aa` **Aa** *Aa* あ 永 Ж Ω

```kotlin
val hello = "hello 你好 こんにちは ПРИВЕТ"
repeat(8) { print(hello) }
```
"""

@Composable
fun AssistantOutputText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val markdownState = rememberMarkdownState(content = text, retainState = true)
    // 原文自带方向控制符时不插入隔离符，复制时也不清理
    val isolate = remember(text) { !BidiText.hasBidiControls(text) }

    BidiSelectionContainer(stripMarks = isolate, modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalBidiIsolation provides isolate) {
            Markdown(
                markdownState = markdownState,
                modifier = Modifier.fillMaxWidth(),
                typography = assistantMarkdownTypography(),
                components = assistantMarkdownComponents(),
            )
        }
    }
}

@Composable
private fun assistantMarkdownComponents(): MarkdownComponents = remember {
    markdownComponents(
        table = { model ->
            ProvideLayoutDirection(rememberBlockDirection(model.content, model.node)) {
                AssistantMarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                )
            }
        },
        codeFence = { model ->
            LtrCode {
                MarkdownCodeFence(model.content, model.node, style = model.typography.code.ltr()) { code, _, style ->
                    AssistantCodeBlock(code = code, style = style)
                }
            }
        },
        codeBlock = { model ->
            LtrCode {
                MarkdownCodeBlock(model.content, model.node, style = model.typography.code.ltr()) { code, _, style ->
                    AssistantCodeBlock(code = code, style = style)
                }
            }
        },
        checkbox = { model -> MarkdownCheckBox(model.content, model.node, model.typography.text) },
        paragraph = { model -> BidiParagraph(model) },
        heading1 = { model -> BidiHeader(model, model.typography.h1) },
        heading2 = { model -> BidiHeader(model, model.typography.h2) },
        heading3 = { model -> BidiHeader(model, model.typography.h3) },
        heading4 = { model -> BidiHeader(model, model.typography.h4) },
        heading5 = { model -> BidiHeader(model, model.typography.h5) },
        heading6 = { model -> BidiHeader(model, model.typography.h6) },
        setextHeading1 = { model -> BidiHeader(model, model.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
        setextHeading2 = { model -> BidiHeader(model, model.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
        blockQuote = { model -> BidiBlockQuote(model) },
        orderedList = { model -> BidiOrderedList(model) },
        unorderedList = { model -> BidiBulletList(model) },
    )
}

private fun TextStyle.ltr(): TextStyle = copy(textDirection = TextDirection.Ltr)

@Composable
private fun assistantMarkdownTypography(
    scale: Float = LocalMarkdownScale.current,
): MarkdownTypography {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = (17f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
    val codeStyle = bodyStyle.copy(
        fontSize = (15f * scale).sp,
        lineHeight = (22f * scale).sp,
        fontFamily = FontFamily.Monospace,
    )
    val quoteStyle = bodyStyle.copy(
        fontSize = (16f * scale).sp,
        lineHeight = (24f * scale).sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return markdownTypography(
        h1 = bodyStyle.copy(
            fontSize = (20f * scale).sp,
            lineHeight = (26f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h2 = bodyStyle.copy(
            fontSize = (18f * scale).sp,
            lineHeight = (24f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h3 = bodyStyle.copy(
            fontSize = (16f * scale).sp,
            lineHeight = (22f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h4 = bodyStyle.copy(
            fontSize = (14.5f * scale).sp,
            lineHeight = (20f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        h5 = bodyStyle.copy(
            fontSize = (13f * scale).sp,
            lineHeight = (18f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        h6 = bodyStyle.copy(
            fontSize = (12f * scale).sp,
            lineHeight = (16f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        text = bodyStyle,
        code = codeStyle,
        inlineCode = codeStyle,
        quote = quoteStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle.copy(fontSize = (13.5f * scale).sp, lineHeight = (18f * scale).sp),
    )
}

@Preview(
    name = "Assistant Markdown Preview",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun AssistantOutputTextPreview() {
    BaseTheme {
        AssistantOutputText(
            text = AssistantMarkdownPreviewText.trim(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
