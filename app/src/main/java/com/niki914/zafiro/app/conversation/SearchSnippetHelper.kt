package com.niki914.zafiro.app.conversation

/**
 * 历史会话搜索文本处理工具：关键词检索匹配与单行上下文 Snippet 截断窗口算法。
 */
object SearchSnippetHelper {
    private const val ELLIPSIS = "..."
    private val WHITESPACE_REGEX = Regex("""\s+""")

    /**
     * 以首个命中的 [query] 为中心截取前后上下文片段（Snippet）。
     *
     * 核心机制：
     * 1. 压平多余空白与换行，确保文本适合在单行预览区域（`maxLines = 1`）展示；
     * 2. 大小写不敏感查找 [query]；
     * 3. 以命中词为中心向前后各保留 [radius] 个字符窗口；
     * 4. 前部截断拼接前缀省略号，后部截断拼接后缀省略号，保障关键词位于可见区域内。
     */
    fun extractSnippet(fullText: String, query: String, radius: Int = 18): String {
        val flattened = fullText.replace(WHITESPACE_REGEX, " ").trim()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            val maxLen = radius * 2
            return if (flattened.length <= maxLen) flattened else flattened.take(maxLen) + ELLIPSIS
        }

        val startIndex = flattened.indexOf(trimmedQuery, ignoreCase = true)
        if (startIndex < 0) {
            val maxLen = radius * 2 + trimmedQuery.length
            return if (flattened.length <= maxLen) flattened else flattened.take(maxLen) + ELLIPSIS
        }

        val endIndex = startIndex + trimmedQuery.length
        val windowStart = (startIndex - radius).coerceAtLeast(0)
        val windowEnd = (endIndex + radius).coerceAtMost(flattened.length)

        val prefix = if (windowStart > 0) ELLIPSIS else ""
        val suffix = if (windowEnd < flattened.length) ELLIPSIS else ""
        val snippet = flattened.substring(windowStart, windowEnd)

        return "$prefix$snippet$suffix"
    }

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
