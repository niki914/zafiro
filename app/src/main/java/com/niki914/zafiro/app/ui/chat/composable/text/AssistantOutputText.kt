package com.niki914.zafiro.app.ui.chat.composable.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.niki914.zafiro.app.ui.chat.composable.body.custom.AssistantCodeBlock
import com.niki914.zafiro.app.ui.content.AssistantMarkdownTable

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

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Markdown(
            markdownState = markdownState,
            modifier = Modifier.fillMaxWidth(),
            typography = assistantMarkdownTypography(),
            components = assistantMarkdownComponents(),
        )
    }
}

@Composable
private fun assistantMarkdownComponents(): MarkdownComponents = remember {
    markdownComponents(
        table = { model ->
            AssistantMarkdownTable(
                content = model.content,
                node = model.node,
                style = model.typography.table,
            )
        },
        codeFence = { model ->
            MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, _, style ->
                AssistantCodeBlock(code = code, style = style)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, _, style ->
                AssistantCodeBlock(code = code, style = style)
            }
        },
        checkbox = { model -> MarkdownCheckBox(model.content, model.node, model.typography.text) },
    )
}

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
