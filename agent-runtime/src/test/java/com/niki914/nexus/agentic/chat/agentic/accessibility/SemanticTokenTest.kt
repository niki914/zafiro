package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTokenTest {

    @Test
    fun parse_standardToken() {
        val result = SemanticToken.parse("a3f2_42")
        assertTrue(result.isSuccess)
        val token = result.getOrThrow()
        assertEquals("a3f2", token.version)
        assertEquals(42, token.index)
    }

    @Test
    fun parse_zeroVersionAndIndex() {
        val result = SemanticToken.parse("0000_0")
        assertTrue(result.isSuccess)
        val token = result.getOrThrow()
        assertEquals("0000", token.version)
        assertEquals(0, token.index)
    }

    @Test
    fun parse_largeIndex() {
        val result = SemanticToken.parse("ffff_999")
        assertTrue(result.isSuccess)
        val token = result.getOrThrow()
        assertEquals("ffff", token.version)
        assertEquals(999, token.index)
    }

    @Test
    fun parse_noUnderscore_fails() {
        val result = SemanticToken.parse("abc")
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_underscoreAtEnd_fails() {
        val result = SemanticToken.parse("abc_")
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_noVersion_fails() {
        val result = SemanticToken.parse("_42")
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_nonNumericIndex_fails() {
        val result = SemanticToken.parse("a3f2_abc")
        assertTrue(result.isFailure)
    }

    @Test
    fun parse_emptyString_fails() {
        val result = SemanticToken.parse("")
        assertTrue(result.isFailure)
    }

    @Test
    fun generate_returnsVersionUnderscoreIndex() {
        assertEquals("a3f2_42", SemanticToken.generate("a3f2", 42))
    }
}
