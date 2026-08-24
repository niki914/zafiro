package com.niki914.zafiro.util

object TextPatternMatcher {
    fun matches(text: String, pattern: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isBlank()) {
            return false
        }
        return try {
            Regex(trimmed).containsMatchIn(text)
        } catch (_: IllegalArgumentException) {
            text.contains(trimmed, ignoreCase = true)
        }
    }

    fun matchesAny(text: String, patterns: List<String>): Boolean {
        return patterns.any { pattern -> matches(text, pattern) }
    }
}
