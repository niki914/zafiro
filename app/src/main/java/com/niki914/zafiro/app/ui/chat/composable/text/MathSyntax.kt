package com.niki914.zafiro.app.ui.chat.composable.text

/**
 * A LaTeX formula found in raw message text.
 *
 * @param latex the formula source without its delimiters
 * @param display true for `$$...$$` / `\[...\]` (block), false for `$...$` / `\(...\)` (inline)
 */
internal data class MathFormula(val latex: String, val display: Boolean)

/**
 * Deterministic placeholder id for [formula] inside an annotated string.
 * Derived from the formula itself, so the markdown annotator (which discovers
 * formulas while rendering) and the pre-built inline-content map agree
 * without any shared mutable state.
 */
internal fun mathPlaceholderId(formula: MathFormula): String =
    "zafiro-math-${if (formula.display) "block" else "inline"}-${formula.latex.hashCode()}"

/**
 * Does this look like a math formula, or like plain text the GFM parser
 * greedily paired between two `$`?
 *
 * The parser turns the first `$` into an opening delimiter and the next `$`
 * into its closing one, so "Pay bills $100 and $50" arrives as the formula
 * "100 and ". Rendering that as LaTeX would mangle ordinary text (and the
 * 0.41.0 annotator would silently drop it instead), so candidates need this
 * check before they are typeset:
 * - any LaTeX command (`\frac`, `\alpha`, ...) is math;
 * - a bare English word of 3+ letters is not math ("and", "for");
 * - otherwise math operators, short variable/number tokens and Greek or
 *   mathematical Unicode characters mark it as math.
 */
internal fun looksLikeMath(latex: String): Boolean {
    val text = latex.trim()
    if (text.isEmpty()) return false
    if ('\\' in text) return true
    val words = text.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    if (words.any { it.length >= 3 }) return false
    if (text.any { it in "^_{}=+*/<>|-().,;:[]!" }) return true
    if (text.any { it.isMathSymbol() }) return true
    if (text.matches(Regex("[A-Za-z0-9]{1,2}"))) return true
    return false
}

private fun Char.isMathSymbol(): Boolean =
    code in 0x370..0x3FF || // Greek
        code in 0x2200..0x22FF || // mathematical operators
        this in "∑∫∏√∞≠≤≥±×÷"

/**
 * Scan raw message text for formulas, skipping fenced code blocks and inline
 * code spans so `` `$x$` `` stays literal code. The scan mirrors what the
 * GFM parser does with dollars (first `$` pairs with the next one), so the
 * formulas found here are the ones the annotator will meet while rendering.
 *
 * Extra entries are harmless (an unused placeholder id is never referenced);
 * a formula missed here still degrades gracefully to its raw source text.
 */
internal fun collectMathFormulas(text: String): List<MathFormula> {
    val found = linkedSetOf<MathFormula>()
    var i = 0
    val n = text.length
    var fenceLength = 0
    var inlineCodeLength = 0
    while (i < n) {
        if (fenceLength == 0 && inlineCodeLength == 0 && (i == 0 || text[i - 1] == '\n')) {
            val fence = fenceRunAt(text, i)
            if (fence > 0) {
                fenceLength = fence
                i += fence
                continue
            }
        }
        if (fenceLength > 0) {
            if (i == 0 || text[i - 1] == '\n') {
                val fence = fenceRunAt(text, i)
                if (fence >= fenceLength) {
                    fenceLength = 0
                    i += fence
                    continue
                }
            }
            i++
            continue
        }
        if (text[i] == '`') {
            val run = backtickRunAt(text, i)
            if (inlineCodeLength == 0) {
                inlineCodeLength = run
            } else if (run == inlineCodeLength) {
                inlineCodeLength = 0
            }
            i += run
            continue
        }
        if (inlineCodeLength > 0) {
            i++
            continue
        }
        if (text[i] == '\\' && i + 1 < n) {
            val next = text[i + 1]
            if (next == '(' || next == '[') {
                val display = next == '['
                val close = if (display) "\\]" else "\\)"
                val end = findUnescaped(text, close, i + 2)
                if (end >= 0 && (display || !text.substring(i + 2, end).contains("\n\n"))) {
                    val latex = text.substring(i + 2, end)
                    if (looksLikeMath(latex)) found += MathFormula(latex, display)
                    i = end + 2
                    continue
                }
                i += 2
                continue
            }
            i += 2
            continue
        }
        if (text[i] == '$') {
            if (i + 1 < n && text[i + 1] == '$') {
                val end = text.indexOf("$$", i + 2)
                if (end >= 0) {
                    val latex = text.substring(i + 2, end).trim()
                    if (looksLikeMath(latex)) found += MathFormula(latex, true)
                    i = end + 2
                    continue
                }
                i += 2
                continue
            }
            var j = i + 1
            var end = -1
            while (j < n) {
                if (text.startsWith("\n\n", j)) break
                if (text[j] == '\\' && j + 1 < n) {
                    j += 2
                    continue
                }
                if (text[j] == '$') {
                    end = j
                    break
                }
                j++
            }
            if (end > i + 1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex)) found += MathFormula(latex, false)
                i = end + 1
                continue
            }
            i++
            continue
        }
        i++
    }
    return found.toList()
}

/** Length of a ``` or ~~~ fence run starting at [i] (line start), or 0. */
private fun fenceRunAt(text: String, i: Int): Int {
    val marker = text[i]
    if (marker != '`' && marker != '~') return 0
    var j = i
    while (j < text.length && text[j] == marker) j++
    return if (j - i >= 3) j - i else 0
}

private fun backtickRunAt(text: String, i: Int): Int {
    var j = i
    while (j < text.length && text[j] == '`') j++
    return j - i
}

/** Index of [needle] at or after [from], ignoring occurrences escaped by a `\`. */
private fun findUnescaped(text: String, needle: String, from: Int): Int {
    var i = text.indexOf(needle, from)
    while (i >= 0) {
        var backslashes = 0
        var k = i - 1
        while (k >= from && text[k] == '\\') {
            backslashes++
            k--
        }
        if (backslashes % 2 == 0) return i
        i = text.indexOf(needle, i + 1)
    }
    return -1
}
