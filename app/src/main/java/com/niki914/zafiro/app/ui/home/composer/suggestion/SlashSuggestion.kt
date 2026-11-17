package com.niki914.zafiro.app.ui.home.composer.suggestion

enum class SlashCategory(val prefix: String) {
    SKILL("skill"),
}

data class SlashSuggestion(
    val id: String,
    val category: SlashCategory,
    val displayText: String,
    val replaceToken: String,
    val searchKeys: List<String> = emptyList(),
)

data class SlashTrigger(
    val slashIndex: Int,
    val query: String,
    val endIndex: Int,
)

/**
 * Checks whether [char] is a CJK ideograph, CJK punctuation, or full-width symbol.
 */
internal fun isCjkOrPunctuation(char: Char): Boolean {
    val block = Character.UnicodeBlock.of(char) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            block == Character.UnicodeBlock.GENERAL_PUNCTUATION
}

/**
 * Parses the text up to [cursorIndex] and detects if a slash command trigger is active.
 *
 * Requirements:
 * 1. Cursor must be at the end of the text (or followed only by whitespace), ignoring mid-sentence slashes.
 * 2. '/' must be at index 0, or preceded by whitespace / newline, or preceded by CJK ideograph / punctuation.
 * 3. Text between '/' and [cursorIndex] cannot contain whitespace.
 */
fun findSlashTrigger(text: String, cursorIndex: Int): SlashTrigger? {
    if (text.isEmpty()) return null
    val safeCursor = cursorIndex.coerceIn(0, text.length)

    // Only trigger if cursor is at the end or followed only by whitespace
    val remaining = text.substring(safeCursor)
    if (remaining.isNotEmpty() && !remaining.all { it.isWhitespace() }) {
        return null
    }

    val prefix = text.substring(0, safeCursor)
    val slashIndex = prefix.lastIndexOf('/')
    if (slashIndex < 0) return null

    val isBoundary = slashIndex == 0 ||
            prefix[slashIndex - 1].isWhitespace() ||
            isCjkOrPunctuation(prefix[slashIndex - 1])
    if (!isBoundary) return null

    val token = prefix.substring(slashIndex + 1)
    if (token.any { it.isWhitespace() }) return null

    return SlashTrigger(
        slashIndex = slashIndex,
        query = token,
        endIndex = safeCursor,
    )
}
