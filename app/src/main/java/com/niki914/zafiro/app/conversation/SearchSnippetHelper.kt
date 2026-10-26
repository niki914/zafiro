package com.niki914.zafiro.app.conversation

/**
 * 历史会话搜索文本处理工具：关键词检索匹配与单行上下文 Snippet 截断窗口算法。
 */
object SearchSnippetHelper {
    private const val ELLIPSIS = "..."
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val MARKDOWN_DECORATION_REGEX = Regex("""[*#`_~>]""")

    /**
     * 以首个命中的 [query] 为中心截取前后上下文片段（Snippet）。
     *
     * 核心机制：
     * 1. 过滤冗余 Markdown 格式符（如粗体 **、标题 # 等）并压平连续空白；
     * 2. [leadingRadius] 前置字符窗口默认保持紧凑（5 字符），确保在手机单行宽度（约 20-25 汉字）内，
     *    关键词始终位于屏幕前半部，绝不因前缀过长被单行末尾的 TextOverflow.Ellipsis 挤出屏幕；
     * 3. [trailingRadius] 适度保留后续语境（默认 18 字符）；
     * 4. 前部溢出拼接前缀省略号，后部溢出拼接后缀省略号。
     */
    fun extractSnippet(
        fullText: String,
        query: String,
        leadingRadius: Int = 5,
        trailingRadius: Int = 18,
    ): String {
        val trimmedQuery = query.trim()
        val markdownRegex = if (trimmedQuery.any { it in "*#`_~>" }) null else MARKDOWN_DECORATION_REGEX
        val cleaned = (if (markdownRegex != null) fullText.replace(markdownRegex, "") else fullText)
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (trimmedQuery.isEmpty() || cleaned.isEmpty()) {
            val maxLen = leadingRadius + trailingRadius
            return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen) + ELLIPSIS
        }

        val startIndex = cleaned.indexOf(trimmedQuery, ignoreCase = true)
        if (startIndex < 0) {
            val maxLen = leadingRadius + trailingRadius + trimmedQuery.length
            return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen) + ELLIPSIS
        }

        val endIndex = startIndex + trimmedQuery.length
        val windowStart = (startIndex - leadingRadius).coerceAtLeast(0)
        val windowEnd = (endIndex + trailingRadius).coerceAtMost(cleaned.length)

        val prefix = if (windowStart > 0) ELLIPSIS else ""
        val suffix = if (windowEnd < cleaned.length) ELLIPSIS else ""
        val snippet = cleaned.substring(windowStart, windowEnd)

        return "$prefix$snippet$suffix"
    }

    /**
     * 兼容单半径入参的便捷重载（前后对称截取）。
     */
    fun extractSnippet(
        fullText: String,
        query: String,
        radius: Int,
    ): String = extractSnippet(
        fullText = fullText,
        query = query,
        leadingRadius = radius,
        trailingRadius = radius,
    )

    /**
     * 查找 [text] 中所有匹配 [query] 的字符区间列表（大小写不敏感）。
     */
    fun findMatchRanges(text: String, query: String): List<IntRange> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() || text.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var searchIndex = 0
        while (searchIndex < text.length) {
            val found = text.indexOf(trimmedQuery, searchIndex, ignoreCase = true)
            if (found < 0) break
            ranges.add(found until (found + trimmedQuery.length))
            searchIndex = found + trimmedQuery.length
        }
        return ranges
    }
}
