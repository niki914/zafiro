package com.niki914.zafiro.app.ui.chat.composable.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The math scanner decides what gets typeset and what stays literal text.
 * A regression here is user-visible either way: real formulas would show as
 * raw `$...$` source, or ordinary text (prices, code) would be mangled into
 * formulas or dropped.
 */
class MathSyntaxTest {

    @Test
    fun inlineMathIsFound() {
        val formulas = collectMathFormulas("The formula \$x^2 + y^2 = r^2\$ is here.")
        assertEquals(listOf(MathFormula("x^2 + y^2 = r^2", false)), formulas)
    }

    @Test
    fun blockMathIsFound() {
        val formulas = collectMathFormulas("\$\$\n\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}\n\$\$")
        assertEquals(
            listOf(MathFormula("\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", true)),
            formulas,
        )
    }

    @Test
    fun parenDelimitersAreFound() {
        val formulas = collectMathFormulas("Inline \\(a+b\\) and block \\[c+d\\] forms.")
        assertEquals(
            listOf(MathFormula("a+b", false), MathFormula("c+d", true)),
            formulas,
        )
    }

    @Test
    fun currencyIsNotMath() {
        assertEquals(emptyList<MathFormula>(), collectMathFormulas("Pay bills \$100 and \$50 please."))
        assertEquals(emptyList<MathFormula>(), collectMathFormulas("Price is \$5."))
    }

    @Test
    fun unclosedDollarStaysLiteral() {
        assertEquals(emptyList<MathFormula>(), collectMathFormulas("Unclosed \$x + y here."))
    }

    @Test
    fun escapedDollarStaysLiteral() {
        assertEquals(emptyList<MathFormula>(), collectMathFormulas("Cost is \\\$5 and \\\$10."))
    }

    @Test
    fun mathInsideCodeIsNotCollected() {
        assertEquals(emptyList<MathFormula>(), collectMathFormulas("Use `\$x\$` in code."))
        assertEquals(
            emptyList<MathFormula>(),
            collectMathFormulas("```\n\$\$x\$\$\n```"),
        )
    }

    @Test
    fun looksLikeMathRejectsPlainWords() {
        assertFalse(looksLikeMath("100 and "))
        assertFalse(looksLikeMath("hello"))
        assertFalse(looksLikeMath(""))
        assertFalse(looksLikeMath("   "))
    }

    @Test
    fun looksLikeMathAcceptsFormulas() {
        assertTrue(looksLikeMath("E=mc^2"))
        assertTrue(looksLikeMath("\\frac{1}{2}"))
        assertTrue(looksLikeMath("x"))
        assertTrue(looksLikeMath("a-b"))
        assertTrue(looksLikeMath("f(x)"))
        assertTrue(looksLikeMath("50"))
    }

    @Test
    fun placeholderIdIsDeterministicAndDistinguishesDisplay() {
        val inline = MathFormula("x^2", false)
        val block = MathFormula("x^2", true)
        assertEquals(mathPlaceholderId(inline), mathPlaceholderId(inline.copy()))
        assertTrue(mathPlaceholderId(inline) != mathPlaceholderId(block))
    }
}
