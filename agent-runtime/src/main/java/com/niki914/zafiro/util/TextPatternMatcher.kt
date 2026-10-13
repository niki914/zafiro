package com.niki914.zafiro.util

object TextPatternMatcher {
    fun matches(text: String, pattern: String): Boolean = find(text, pattern) != null

    /**
     * 返回实际命中的文本（正则的第一个匹配片段，关键字则返回关键字本身）。
     * 供确认弹窗展示「为什么被拦」：命中 `\brm\s+-rf\b` 的片段是 `rm -rf`，比正则好读。
     */
    fun find(text: String, pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return try {
            Regex(trimmed).find(text)?.value?.takeIf(String::isNotEmpty)
        } catch (_: IllegalArgumentException) {
            trimmed.takeIf { text.contains(it, ignoreCase = true) }
        }
    }

    fun matchesAny(text: String, patterns: List<String>): Boolean {
        return patterns.any { pattern -> matches(text, pattern) }
    }
}
