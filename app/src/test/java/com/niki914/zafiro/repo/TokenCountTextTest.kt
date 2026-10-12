package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenCountTextTest {

    @Test
    fun parse_plainNumbers() {
        assertEquals(128_000L, TokenCountText.parse("128000"))
        assertEquals(1_000L, TokenCountText.parse("1000"))
        assertEquals(1_048_576L, TokenCountText.parse("1048576"))
    }

    @Test
    fun parse_suffixes_caseAndSpaceInsensitive() {
        assertEquals(1_000_000L, TokenCountText.parse("1M"))
        assertEquals(1_000_000L, TokenCountText.parse("1 m"))
        assertEquals(1_000_000L, TokenCountText.parse("  1m "))
        assertEquals(200_000L, TokenCountText.parse("200k"))
        assertEquals(1_000L, TokenCountText.parse("1K"))
        assertEquals(2_000_000_000L, TokenCountText.parse("2b"))
        assertEquals(2_000_000_000L, TokenCountText.parse("2 B"))
    }

    @Test
    fun parse_rejectsInvalid() {
        assertNull(TokenCountText.parse(""))
        assertNull(TokenCountText.parse("   "))
        assertNull(TokenCountText.parse("abc"))
        assertNull(TokenCountText.parse("-5"))
        assertNull(TokenCountText.parse("1.5k"))
        assertNull(TokenCountText.parse("1x"))
        assertNull(TokenCountText.parse("k"))
        assertNull(TokenCountText.parse("1kb"))
    }

    @Test
    fun parse_rejectsOverflow() {
        // 溢出必须回 null，不能落成错误的“小值”：位数超 Long 与乘法 wrap 成负数
        assertNull(TokenCountText.parse("99999999999999999999b"))
        assertNull(TokenCountText.parse("92233720368547758b"))
        // wrap 成整数也是错的：1e17 × 1e3 溢出后回 +7.7e18，曾被当成合法值
        assertNull(TokenCountText.parse("100000000000000000k"))
        // 边界内允许（b = 十亿倍，Long.MAX/1e9 ≈ 9.22e9）
        assertEquals(9_223_372_036_000_000_000L, TokenCountText.parse("9223372036b"))
    }
}
