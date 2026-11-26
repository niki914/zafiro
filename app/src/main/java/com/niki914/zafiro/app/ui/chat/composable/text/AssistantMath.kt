package com.niki914.zafiro.app.ui.chat.composable.text

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexThemeColors
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * LaTeX math inside assistant messages.
 *
 * The GFM parser already turns `$...$` / `$$...$$` into INLINE_MATH /
 * BLOCK_MATH nodes, but the 0.41.0 annotator drops those nodes, so formulas
 * vanish from chat entirely. This wires the parser's math nodes to the
 * native Compose LaTeX renderer instead:
 * - the annotator below swaps each math node for an inline-content
 *   placeholder (bold/italic/links around it keep working, code spans are
 *   left alone because their TEXT children report a CODE_SPAN parent);
 * - [rememberMathInlineContent] pre-measures every formula in the message
 *   so the placeholders have something to render;
 * - a paragraph that is a single `$$...$$` block is rendered centered with
 *   [LatexAutoWrap] instead of as an inline placeholder.
 * `\(...\)` / `\[...\]` are not math nodes for the parser, so the annotator
 * also scans plain TEXT nodes for them.
 *
 * Anything that does not look like math ([looksLikeMath]) is kept as raw
 * source text, so prices like "$100 and $50" render unchanged.
 */
@Composable
internal fun rememberMathAnnotator(): MarkdownAnnotator = remember {
    markdownAnnotator { content, child ->
        when (child.type) {
            GFMElementTypes.INLINE_MATH -> {
                val raw = child.getTextInNode(content).toString()
                val latex = raw.removeSurrounding("$", "$")
                if (looksLikeMath(latex)) {
                    appendInlineContent(mathPlaceholderId(MathFormula(latex, false)), latex)
                } else {
                    append(raw)
                }
                true
            }

            GFMElementTypes.BLOCK_MATH -> {
                val raw = child.getTextInNode(content).toString()
                val latex = raw.removeSurrounding("$$", "$$").trim()
                if (looksLikeMath(latex)) {
                    appendInlineContent(mathPlaceholderId(MathFormula(latex, true)), latex)
                } else {
                    append(raw)
                }
                true
            }

            MarkdownTokenTypes.TEXT -> {
                if (child.parent?.type == MarkdownElementTypes.CODE_SPAN) {
                    false
                } else {
                    appendParenMath(child.getTextInNode(content).toString())
                }
            }

            else -> false
        }
    }
}

/** `\(...\)` / `\[...\]` occurrences in [raw]; appends them as placeholders. */
private fun AnnotatedString.Builder.appendParenMath(raw: String): Boolean {
    var consumed = false
    var cursor = 0
    for (match in findParenMath(raw)) {
        if (!looksLikeMath(match.latex)) continue
        append(raw.substring(cursor, match.start))
        appendInlineContent(mathPlaceholderId(MathFormula(match.latex, match.display)), match.latex)
        cursor = match.end
        consumed = true
    }
    if (!consumed) return false
    append(raw.substring(cursor))
    return true
}

private data class ParenMath(val start: Int, val end: Int, val latex: String, val display: Boolean)

private fun findParenMath(raw: String): List<ParenMath> {
    val found = mutableListOf<ParenMath>()
    var i = 0
    while (i < raw.length - 1) {
        if (raw[i] == '\\' && (raw[i + 1] == '(' || raw[i + 1] == '[') && !isEscaped(raw, i)) {
            val display = raw[i + 1] == '['
            val close = if (display) "\\]" else "\\)"
            val end = findUnescapedParen(raw, close, i + 2)
            if (end >= 0) {
                found += ParenMath(i, end + 2, raw.substring(i + 2, end), display)
                i = end + 2
                continue
            }
        }
        i++
    }
    return found
}

/** True when the `\` at [i] is itself escaped by an odd run of backslashes. */
private fun isEscaped(raw: String, i: Int): Boolean {
    var backslashes = 0
    var k = i - 1
    while (k >= 0 && raw[k] == '\\') {
        backslashes++
        k--
    }
    return backslashes % 2 == 1
}

private fun findUnescapedParen(raw: String, needle: String, from: Int): Int {
    var i = raw.indexOf(needle, from)
    while (i >= 0) {
        if (!isEscaped(raw, i)) return i
        i = raw.indexOf(needle, i + 1)
    }
    return -1
}

/**
 * Measures every formula in [text] once and maps placeholder ids to their
 * inline content. A formula the renderer cannot measure (for example an
 * unfinished one mid-stream) is left out, so its placeholder falls back to
 * the raw LaTeX source as plain text.
 */
@Composable
internal fun rememberMathInlineContent(text: String): MarkdownInlineContent {
    val measurer = rememberLatexMeasurer()
    val inlineConfig = rememberMathLatexConfig(display = false)
    val displayConfig = rememberMathLatexConfig(display = true)
    val isDarkTheme = isSystemInDarkTheme()
    val entries = remember(text, measurer, inlineConfig, displayConfig, isDarkTheme) {
        collectMathFormulas(text).mapNotNull { formula ->
            val rendered = measurer.inlineContent(
                latex = formula.latex,
                config = if (formula.display) displayConfig else inlineConfig,
                isDarkTheme = isDarkTheme,
            ) ?: return@mapNotNull null
            mathPlaceholderId(formula) to rendered
        }.toMap()
    }
    return markdownInlineContent(entries)
}

@Composable
internal fun rememberMathLatexConfig(display: Boolean): LatexConfig {
    val scale = LocalMarkdownScale.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    return remember(scale, onSurface, display) {
        LatexConfig(
            fontSize = ((if (display) 20f else 17f) * scale).sp,
            theme = LatexTheme.auto(
                light = LatexThemeColors(color = onSurface, backgroundColor = Color.Transparent),
                dark = LatexThemeColors(color = onSurface, backgroundColor = Color.Transparent),
            ),
        )
    }
}

/**
 * A paragraph that is exactly one `$$...$$` block, rendered centered and
 * display-sized; anything else renders as a normal paragraph.
 */
@Composable
internal fun MathParagraph(model: MarkdownComponentModel) {
    val displayConfig = rememberMathLatexConfig(display = true)
    val block = singleBlockMath(model)
    if (block == null) {
        MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
        return
    }
    if (looksLikeMath(block.latex)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            LatexAutoWrap(latex = block.latex, config = displayConfig)
        }
    } else {
        // Paired $$ around plain text: show the raw source, the default
        // annotator would silently drop it.
        Text(text = block.raw, style = model.typography.paragraph)
    }
}

private data class BlockMath(val raw: String, val latex: String)

/** The paragraph's single `$$...$$` block, if it is the paragraph's only child. */
private fun singleBlockMath(model: MarkdownComponentModel): BlockMath? {
    val children = model.node.children
    if (children.size != 1) return null
    val block = children[0]
    if (block.type != GFMElementTypes.BLOCK_MATH) return null
    val raw = block.getTextInNode(model.content).toString()
    return BlockMath(raw = raw, latex = raw.removeSurrounding("$$", "$$").trim())
}
