package com.niki914.zafiro.app.ui.home.composer.suggestion

/**
 * Intelligent fuzzy matching for slash suggestions.
 *
 * Supports:
 * - Empty query: returns all items.
 * - Prefix matching: e.g. query "p" on "phone-use" -> prefix hit.
 * - Word boundary / Acronym matching: e.g. query "pu" on "phone-use" -> 'p' in 'phone', 'u' in 'use'.
 * - Subsequence matching: e.g. query "po" on "phone-use" -> 'p' and 'o' in 'phone'.
 * - Category prefix matching: e.g. query "skill:p" or "sk" -> recognizes category filter.
 */
object SlashFuzzyMatcher {

    fun match(query: String, candidates: List<SlashSuggestion>): List<SlashSuggestion> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) {
            return candidates
        }

        val hasCategoryPrefix = trimmed.contains(':')
        val targetCategory = if (hasCategoryPrefix) trimmed.substringBefore(':') else null
        val effectiveQuery = if (hasCategoryPrefix) trimmed.substringAfter(':') else trimmed

        return candidates
            .mapNotNull { candidate ->
                if (targetCategory != null && !candidate.category.prefix.startsWith(targetCategory)) {
                    return@mapNotNull null
                }
                val score = calculateScore(effectiveQuery, trimmed, candidate, hasCategoryPrefix)
                if (score > 0) candidate to score else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun calculateScore(
        effectiveQuery: String,
        rawQuery: String,
        candidate: SlashSuggestion,
        hasCategoryPrefix: Boolean,
    ): Int {
        if (hasCategoryPrefix) {
            if (effectiveQuery.isEmpty()) {
                return 500 - candidate.id.length
            }
            val targets = listOf(candidate.id) + candidate.searchKeys
            return targets.maxOfOrNull { scoreTarget(effectiveQuery, it) } ?: 0
        }

        // Direct category prefix matching (e.g. user typed "/sk" or "/skill")
        // Require at least 2 characters (e.g. "sk") to avoid single-letter "s" hijacking all skills
        val categoryScore = if (rawQuery.length >= 2 && candidate.category.prefix.startsWith(rawQuery)) {
            800 - candidate.id.length
        } else {
            0
        }

        val targets = listOf(candidate.id) + candidate.searchKeys
        var maxScore = categoryScore

        for (target in targets) {
            val score = scoreTarget(effectiveQuery, target)
            if (score > maxScore) {
                maxScore = score
            }
        }

        return maxScore
    }

    private fun scoreTarget(query: String, target: String): Int {
        if (query.isEmpty()) return 100
        if (target.startsWith(query, ignoreCase = true)) {
            return 1000 + (100 - target.length).coerceAtLeast(0)
        }

        var qIdx = 0
        var score = 0
        var prevMatchIdx = -2

        for (tIdx in target.indices) {
            if (qIdx < query.length && target[tIdx].equals(query[qIdx], ignoreCase = true)) {
                val isWordBoundary = tIdx == 0 ||
                        target[tIdx - 1] in "-_ " ||
                        (target[tIdx].isUpperCase() && !target[tIdx - 1].isUpperCase())

                if (isWordBoundary) {
                    score += 150
                }
                if (tIdx == prevMatchIdx + 1) {
                    score += 40
                }
                score += 20
                prevMatchIdx = tIdx
                qIdx++
            }
        }

        return if (qIdx == query.length) {
            (score - target.length).coerceAtLeast(1)
        } else {
            0
        }
    }
}
