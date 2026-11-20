package com.niki914.zafiro.app.ui.home.composer.suggestion

enum class SlashCategory(val prefix: String) {
    COMMAND("cmd"),
    SKILL("skill"),
    PROMPT("prompt"),
}

enum class SuggestionScope {
    /** 独立指令：整行仅有该命令且无附件时方可触发建议 (如 /compact, /new) */
    STANDALONE,

    /** 行内指令：可在任意合法词边界触发 (如 /skill:xxx) */
    ANYWHERE,
}

sealed interface SuggestionAction {
    /** 文本插入/补全型：将触发的 /token 替换为 [replaceToken] */
    data class Insert(val replaceToken: String) : SuggestionAction

    /** 立即提交型：替换文本为 [replaceToken] 并立即自动发送 */
    data class Submit(val replaceToken: String) : SuggestionAction
}

data class SlashSuggestion(
    val id: String,
    val category: SlashCategory,
    val displayText: String,
    val replaceToken: String,
    val scope: SuggestionScope = SuggestionScope.ANYWHERE,
    val action: SuggestionAction = SuggestionAction.Insert(replaceToken),
    val searchKeys: List<String> = emptyList(),
)

sealed class BuiltinSlashCommand(
    val id: String,
    val command: String,
    val searchKeys: List<String>,
    val autoSubmit: Boolean = false,
) {
    data object NewSession : BuiltinSlashCommand(
        id = "new",
        command = "/new",
        searchKeys = listOf("new", "reset", "xinjian", "新建", "新对话", "新会话"),
        autoSubmit = false,
    )

    data object Compact : BuiltinSlashCommand(
        id = "compact",
        command = "/compact",
        searchKeys = listOf("compact", "compress", "yasuo", "压缩"),
        autoSubmit = false,
    )

    data object VirtualDisplay : BuiltinSlashCommand(
        id = "vd",
        command = "/vd",
        searchKeys = listOf("vd", "vs", "virtual", "screen", "display", "screen", "xuniping", "虚拟屏"),
        autoSubmit = true,
    )

    fun toSuggestion(): SlashSuggestion = SlashSuggestion(
        id = id,
        category = SlashCategory.COMMAND,
        displayText = command,
        replaceToken = command,
        scope = SuggestionScope.STANDALONE,
        action = if (autoSubmit) SuggestionAction.Submit(command) else SuggestionAction.Insert(command),
        searchKeys = searchKeys,
    )

    companion object {
        val ALL: List<BuiltinSlashCommand>
            get() = listOf(NewSession, Compact, VirtualDisplay)

        fun find(input: String): BuiltinSlashCommand? {
            val trimmed = input.trim()
            return ALL.find { it.command.equals(trimmed, ignoreCase = true) }
        }
    }
}

data class SlashTrigger(
    val slashIndex: Int,
    val query: String,
    val endIndex: Int,
)

fun SlashTrigger.isStandalone(input: String, hasAttachments: Boolean): Boolean {
    if (hasAttachments) return false
    val safeSlashIndex = slashIndex.coerceIn(0, input.length)
    val safeEndIndex = endIndex.coerceIn(0, input.length)
    return input.substring(0, safeSlashIndex).all { it.isWhitespace() } &&
            input.substring(safeEndIndex).all { it.isWhitespace() }
}

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
