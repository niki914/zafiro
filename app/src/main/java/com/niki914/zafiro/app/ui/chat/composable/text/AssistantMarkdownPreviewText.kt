package com.niki914.zafiro.app.ui.chat.composable.text

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

Inline ${'$'}E = mc^2${'$'} and ${'$'}x^2 + y^2 = r^2${'$'} stay in the line.

${'$'}${'$'}
\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
${'$'}${'$'}

Pay ${'$'}100 and ${'$'}50, not math.

```kotlin
val hello = "hello 你好 こんにちは ПРИВЕТ"
repeat(8) { print(hello) }
```
"""
