package com.niki914.uikit.text

/** 一段用户或模型文本的书写方向，由这段文本自己的词决定。 */
enum class BidiDirection { Ltr, Rtl }

/**
 * 希伯来语、阿拉伯语、波斯语等 RTL 文本的方向判定与显示隔离，纯计算，不依赖 Compose。
 *
 * 方向按「词」投票，而不是看第一个强方向字符：RTL 句子经常以拉丁词开头
 * （"Claude Code על הטלפון"、"Zafiro يعمل"），首字符判定会把整段排成 LTR。
 *
 * 插入的方向控制符只用于显示：存储、发送、复制的仍是原文（复制时由 [stripInsertedMarks] 还原）。
 * 原文自带方向控制符时一律不投票、不插入，尊重作者的方向。
 */
object BidiText {

    /** LEFT-TO-RIGHT MARK */
    const val LRM = '\u200E'

    /** RIGHT-TO-LEFT MARK */
    const val RLM = '\u200F'

    /** LEFT-TO-RIGHT ISOLATE：把一段 LTR 内容（代码、路径、数字区间）隔离在 RTL 段落里。 */
    const val LRI = '\u2066'

    /** RIGHT-TO-LEFT ISOLATE */
    const val RLI = '\u2067'

    private const val FSI = '\u2068'

    // 判断上下文时代替被隔离孤岛内容的中性字符（OBJECT REPLACEMENT CHARACTER，双向类别 ON）
    private const val NEUTRAL = '\uFFFC'

    /** POP DIRECTIONAL ISOLATE：结束 [LRI] / [RLI]。 */
    const val PDI = '\u2069'

    private const val RTL_RATIO = 0.4
    private const val RTL_RATIO_WHEN_FIRST_WORD_RTL = 0.25

    private val whitespace = Regex("\\s+")
    private val token = Regex("\\S+")

    // 词两侧属于正文的标点：包在技术词外面的括号、引号留在隔离区外，避免成对的括号被拆到两个方向里
    private const val OPENING_PUNCTUATION = "([{\"'«“‘"
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}\"'»”’"
    private const val OPENING_BRACKETS = "([{"
    private const val CLOSING_BRACKETS = ")]}"
    private val flag = Regex("^--?[A-Za-z][\\w.=-]*$")

    // 只认 ASCII 数字：阿拉伯-印度数字（٠-٩）在 RTL 段落里本来就按阿拉伯语习惯排列
    private const val NUMBER = "[0-9]+(?:[.,:][0-9]+)*%?"

    // 两个数字之间夹着 en/em dash、减号、带空格的连字符或运算符时，RTL 段落会把它们画反
    // （10–20 → 20–10，6*7 → 7*6），整段作为一个 LTR 孤岛。先匹配完整的数字串（日期、版本号也整段匹配），
    // 再整体决定要不要隔离，避免从 12/11/2026、v1.2.3 的中间重新匹配出半段
    private val numberRun =
        Regex("$NUMBER(?:[ \\t]*[\\u2013\\u2014\\u2212×÷+*/=^-][ \\t]*$NUMBER)*")
    private val singleNumber = Regex(NUMBER)

    // 纯文本里用反引号包住的行内代码（用户输入或未渲染的 Markdown）
    private val codeSpan = Regex("`[^`\\n]+`")

    /**
     * 按词投票：至少 40% 的投票词含 RTL 字母，或首个投票词是 RTL 且至少 25%，判为 RTL。
     * 不含字母的词（数字、符号）与技术词（URL、路径、命令行参数）不投票。
     * [backtickCode] 为 true 时（原始文本：用户输入、未渲染的 Markdown）反引号里的代码也不投票；
     * 已解析的 Markdown 文本里的反引号是字面字符，传 false。
     * 没有投票词、或原文自带方向控制符时返回 null，由调用方按内容（首个强字符）排版。
     */
    fun directionOf(text: CharSequence, backtickCode: Boolean = true): BidiDirection? {
        if (hasBidiControls(text)) return null
        var voters = 0
        var rtlVoters = 0
        var first: BidiDirection? = null
        val prose = if (backtickCode) codeSpan.replace(text, " ") else text
        for (word in prose.split(whitespace)) {
            val vote = voteOf(word) ?: continue
            voters++
            if (vote == BidiDirection.Rtl) rtlVoters++
            if (first == null) first = vote
        }
        if (voters == 0) return null
        val ratio = rtlVoters.toDouble() / voters
        val rtl = ratio >= RTL_RATIO || (first == BidiDirection.Rtl && ratio >= RTL_RATIO_WHEN_FIRST_WORD_RTL)
        return if (rtl) BidiDirection.Rtl else BidiDirection.Ltr
    }

    /** 输入框用：多段文本无法逐段指定方向，返回 null 交给平台按段首个强字符判定。 */
    fun fieldDirectionOf(text: CharSequence): BidiDirection? =
        if ('\n' in text) null else directionOf(text)

    /**
     * 纯文本的显示形态：逐段（按 `\n`）投票，投票结果与平台的首字符判定不一致时，
     * 在段首插入 [RLM] / [LRM]，让 `TextDirection.Content` 按段得到正确方向；
     * RTL 段落里的 LTR 孤岛（见 [ltrIslands]）按 [islandMarks] 标出。不改变段数与行数。
     */
    fun forDisplay(text: String): String {
        if (text.isEmpty() || hasBidiControls(text)) return text
        val out = StringBuilder(text.length + 8)
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            out.append(paragraphForDisplay(text.substring(start, end)))
            if (end == text.length) break
            out.append('\n')
            start = end + 1
        }
        return out.toString()
    }

    /**
     * RTL 段落里需要整体隔离的 LTR 孤岛：技术词（不含两侧正文标点）、数字区间与算式，
     * 加上调用方给出的 [extra]（如行内代码的位置）；[backtickCode] 为 true 时还有反引号里的代码（只用于原始文本）。
     * 返回按起点排序、互不重叠的区间。
     */
    fun ltrIslands(
        text: CharSequence,
        extra: List<IntRange> = emptyList(),
        backtickCode: Boolean = false,
    ): List<IntRange> {
        val ranges = ArrayList<IntRange>(extra)
        token.findAll(text).forEach { match ->
            val core = proseCore(match.value)
            if (!core.isEmpty() && isTechnicalToken(match.value.substring(core.first, core.last + 1))) {
                ranges += (match.range.first + core.first)..(match.range.first + core.last)
            }
        }
        numberRun.findAll(text).forEach { match ->
            // 紧跟在拉丁字母后的数字串（v2-3、COVID-19、v1.2.3+4）属于那个词，不单独隔离
            val before = text.getOrNull(match.range.first - 1)
            val partOfLtrWord = before != null && Character.isLetter(before) && !isRtl(before.code)
            if (!partOfLtrWord && !singleNumber.matches(match.value)) ranges += match.range
        }
        if (backtickCode) codeSpan.findAll(text).forEach { ranges += it.range }
        return merge(ranges)
    }

    /** 按 [islandMarks] 标出 [ltrIslands] 找到的孤岛。[text] 是原始文本，反引号里的代码也是孤岛。 */
    fun isolateLtrIslands(text: String): String {
        val islands = ltrIslands(text, backtickCode = true)
        if (islands.isEmpty()) return text
        val out = StringBuilder(text.length + islands.size * 2)
        var cursor = 0
        for ((island, marks) in islands.zip(islandMarks(text, islands))) {
            val (open, close) = marks
            out.append(text, cursor, island.first).append(open)
            out.append(text, island.first, island.last + 1).append(close)
            cursor = island.last + 1
        }
        return out.append(text, cursor, text.length).toString()
    }

    /**
     * 每个孤岛两端插入的字符：
     * - 含 RTL 字母或阿拉伯-印度数字（如含希伯来语的行内代码）：[LRI]…[PDI]，让它内部按 LTR 排；
     * - 含 LTR 字母（路径、参数、英文代码）：两端各一个 [LRM]。孤岛边缘的标点随之按 LTR 排，紧挨着的拉丁词
     *   与它连成一个 LTR 段（`npm run build --prod` 不会被拆开按 RTL 顺序排），孤岛之后的数字本来就跟着它的字母按 LTR 排；
     * - 不含字母（数字区间、算式）：前面最近的强字符是 LTR（`Python (3.10–3.12)`，后面的数字本来就按 LTR 排），
     *   或后面隔着空白紧跟一个拉丁词（`10–20 seconds`）时用 [LRM]，否则用 [LRI]…[PDI]：
     *   结尾的 LRM 会把后面的数字也拉进 LTR 段，打乱 `1, 10–20, 2, 3` 这样的列表。
     * 判断上下文时，用 [LRI]…[PDI] 隔离的孤岛对外是中性的，它里面的字母不算。
     */
    internal fun islandMarks(text: CharSequence, islands: List<IntRange>): List<Pair<Char, Char>> {
        if (islands.isEmpty()) return emptyList()
        val contents = islands.map { contentDirection(text, it) }
        val exterior = StringBuilder(text)
        islands.forEachIndexed { index, island ->
            if (contents[index] != BidiDirection.Rtl) return@forEachIndexed
            // 保留换行：上下文扫描在每行开头重新开始
            for (offset in island) if (exterior[offset] != '\n') exterior.setCharAt(offset, NEUTRAL)
        }
        val before = strongBefore(exterior)
        return islands.mapIndexed { index, island ->
            when (contents[index]) {
                BidiDirection.Rtl -> LRI to PDI
                BidiDirection.Ltr -> LRM to LRM
                null -> {
                    val joinsLatin = before[island.first] == BidiDirection.Ltr || latinFollows(exterior, island.last + 1)
                    if (joinsLatin) LRM to LRM else LRI to PDI
                }
            }
        }
    }

    /**
     * 孤岛内容的方向：有 RTL 字母或阿拉伯-印度数字（在 RTL 段落里按 RTL 参与排序）为 Rtl，
     * 否则有 LTR 字母为 Ltr，都没有为 null。按码点判断。
     */
    private fun contentDirection(text: CharSequence, island: IntRange): BidiDirection? {
        var hasLtr = false
        var index = island.first
        while (index <= island.last) {
            val codePoint = Character.codePointAt(text, index)
            if (Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_ARABIC_NUMBER) return BidiDirection.Rtl
            when (strongOf(codePoint)) {
                BidiDirection.Rtl -> return BidiDirection.Rtl
                BidiDirection.Ltr -> hasLtr = true
                null -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return if (hasLtr) BidiDirection.Ltr else null
    }

    /** 每个位置之前（同一行内）最近的强字符方向；下标 i 不含 i 本身。 */
    private fun strongBefore(text: CharSequence): Array<BidiDirection?> {
        val result = arrayOfNulls<BidiDirection>(text.length + 1)
        var last: BidiDirection? = null
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val count = Character.charCount(codePoint)
            for (offset in 0 until count) result[index + offset] = last
            last = if (codePoint == '\n'.code) null else strongOf(codePoint) ?: last
            index += count
        }
        result[text.length] = last
        return result
    }

    /** [from] 起（同一行内）跳过空白后的第一个字符是否是 LTR 强字符。 */
    private fun latinFollows(text: CharSequence, from: Int): Boolean {
        var index = from
        while (index < text.length && text[index] != '\n' && text[index].isWhitespace()) index++
        return index < text.length && strongOf(Character.codePointAt(text, index)) == BidiDirection.Ltr
    }

    /** 原文是否自带方向控制符；自带时不插入任何控制符。 */
    fun hasBidiControls(text: CharSequence): Boolean = text.any(::isBidiControl)

    /** 去掉显示时插入的 [LRM] / [RLM] / [LRI] / [RLI] / [PDI]，仅用于原文本不含控制符的场景。 */
    fun stripInsertedMarks(text: String): String =
        if (text.none(::isInsertedMark)) text else text.filterNot(::isInsertedMark)

    /** 用 [LRI] 或 [RLI] 隔离一段嵌在其他文本里的用户/模型内容（如工具意图、拒绝原因）。 */
    fun isolate(text: String): String {
        if (text.isEmpty() || hasBidiControls(text)) return text
        if (directionOf(text) != BidiDirection.Rtl) return "$LRI$text$PDI"
        // RTL 隔离区里的区间、路径同样会被打乱，内部也标出孤岛
        return "$RLI${isolateLtrIslands(text)}$PDI"
    }

    private fun paragraphForDisplay(paragraph: String): String {
        val vote = directionOf(paragraph) ?: return paragraph
        val body = if (vote == BidiDirection.Rtl) isolateLtrIslands(paragraph) else paragraph
        // 按插入标记后的文字判断：以孤岛开头的段落首个强字符是 LRM
        val firstStrong = firstStrongDirection(body)
        return when {
            vote == BidiDirection.Rtl && firstStrong != BidiDirection.Rtl -> "$RLM$body"
            vote == BidiDirection.Ltr && firstStrong == BidiDirection.Rtl -> "$LRM$body"
            else -> body
        }
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * 词去掉两侧正文标点后的本体位置。属于词本身的成对括号保留在本体里：
     * `(../src/app.ts)` 的两个括号都在外面，`src/(old)` 与 `https://example.com/a(b)` 保持完整。
     */
    private fun proseCore(word: String): IntRange {
        var start = 0
        while (start < word.length && word[start] in OPENING_PUNCTUATION) start++
        var end = word.length
        // 本体里每种括号的左、右括号数，两端增减时同步更新，长串括号也只扫描一遍
        val opens = IntArray(OPENING_BRACKETS.length)
        val closes = IntArray(CLOSING_BRACKETS.length)
        for (index in start until end) {
            OPENING_BRACKETS.indexOf(word[index]).let { if (it >= 0) opens[it]++ }
            CLOSING_BRACKETS.indexOf(word[index]).let { if (it >= 0) closes[it]++ }
        }
        while (end > start && word[end - 1] in TRAILING_PUNCTUATION) {
            val pair = CLOSING_BRACKETS.indexOf(word[end - 1])
            if (pair >= 0) {
                // 右括号闭合了词内的左括号时，它属于词本身
                if (opens[pair] > closes[pair] - 1) break
                closes[pair]--
            }
            end--
        }
        // 开头去掉的左括号在词内还有对应的右括号时，它属于词本身
        while (start > 0) {
            val pair = OPENING_BRACKETS.indexOf(word[start - 1])
            if (pair < 0 || closes[pair] <= opens[pair]) break
            opens[pair]++
            start--
        }
        return start until end
    }

    private fun voteOf(word: String): BidiDirection? {
        val core = proseCore(word)
        if (word.isEmpty() || (!core.isEmpty() && isTechnicalToken(word.substring(core.first, core.last + 1)))) {
            return null
        }
        var hasLetter = false
        var index = 0
        while (index < word.length) {
            val codePoint = Character.codePointAt(word, index)
            if (Character.isLetter(codePoint)) {
                if (isRtl(codePoint)) return BidiDirection.Rtl
                hasLetter = true
            }
            index += Character.charCount(codePoint)
        }
        return if (hasLetter) BidiDirection.Ltr else null
    }

    // 与 Unicode 双向算法 P2 一致：跳过隔离符（LRI/RLI/FSI…PDI）里的内容
    private fun firstStrongDirection(text: String): BidiDirection? {
        var index = 0
        var isolates = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                codePoint in LRI.code..FSI.code -> isolates++
                codePoint == PDI.code -> if (isolates > 0) isolates--
                isolates == 0 -> strongOf(codePoint)?.let { return it }
            }
            index += Character.charCount(codePoint)
        }
        return null
    }

    private fun strongOf(codePoint: Int): BidiDirection? = when {
        isRtl(codePoint) -> BidiDirection.Rtl
        Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_LEFT_TO_RIGHT -> BidiDirection.Ltr
        else -> null
    }

    // URL、路径、命令行参数：不投票，在 RTL 段落里作为 LTR 孤岛显示。含 RTL 字母的词（如 כן/לא）是正文。
    private fun isTechnicalToken(word: String): Boolean {
        if (word.isEmpty() || word.any { isRtl(it.code) }) return false
        return "://" in word || word.startsWith("www.") || '/' in word || '\\' in word ||
            ('@' in word && '.' in word) || flag.matches(word)
    }

    private fun isRtl(codePoint: Int): Boolean {
        val directionality = Character.getDirectionality(codePoint)
        return directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    private fun isBidiControl(char: Char): Boolean =
        char == LRM || char == RLM || char == '\u061C' || char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069'

    private fun isInsertedMark(char: Char): Boolean =
        char == LRM || char == RLM || char == LRI || char == RLI || char == PDI
}
