package com.niki914.zafiro.app.trigger

/**
 * 解析 ACTION_PROCESS_TEXT 交付的两个 extra。纯函数，无 Context 依赖。
 *
 * 处理来源 App 的脏数据：BOM、零宽/format 字符、换行归一。
 * 空白用 trim 后的形态判定，但发送原始未 trim 的文本（段落缩进是内容）。
 *
 * 参考实现：SwiftSlate（MIT License, © 2026 Musheer Alam）ProcessTextInput。
 */
object TextSelectionInput {

    private const val BOM = '\uFEFF' // U+FEFF，某些宿主会前置
    private const val ZERO_WIDTH_SPACE = '\u200B' // U+200B
    private const val ZWNJ = '\u200C' // U+200C
    private const val ZWJ = '\u200D' // U+200D
    private const val WORD_JOINER = '\u2060' // U+2060
    private const val SOFT_HYPHEN = '\u00AD' // U+00AD

    /** @return 不可用的选中文本返回 null（extra 缺失或仅空白/不可见字符）。 */
    fun parse(rawText: CharSequence?): String? {
        if (rawText == null) return null

        var s = rawText.toString()
        s = s.removePrefix(BOM.toString())
        s = s.replace("\r\n", "\n").replace('\r', '\n')

        if (s.trim().all { it.isWhitespace() || isInvisible(it) }) return null
        return s
    }

    /** 零宽与 bidi/format 字符：视觉上不存在，不算有意义内容。 */
    private fun isInvisible(c: Char): Boolean =
        c == ZERO_WIDTH_SPACE || c == ZWNJ || c == ZWJ || c == WORD_JOINER ||
                c == SOFT_HYPHEN || c == BOM ||
                c.category == CharCategory.FORMAT
}
