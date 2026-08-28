package com.niki914.zafiro.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputTruncatorTest {

    @Test
    fun head_withinLimits_returnsUntouched() {
        val result = ToolOutputTruncator.truncateHead("a\nb\nc")
        assertFalse(result.truncated)
        assertEquals("a\nb\nc", result.content)
    }

    @Test
    fun head_overLineLimit_keepsFirstLines() {
        val content = (1..2500).joinToString("\n") { "line $it" }
        val result = ToolOutputTruncator.truncateHead(content, maxLines = 10)

        assertTrue(result.truncated)
        assertEquals(2500, result.totalLines)
        assertEquals("line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10", result.content)
    }

    @Test
    fun head_overByteLimit_stopsAtCompleteLines() {
        val content = "aa\nbb\ncc\ndd"
        val result = ToolOutputTruncator.truncateHead(content, maxLines = 100, maxBytes = 7)

        assertTrue(result.truncated)
        // aa(2) + \n(1) + bb(2) = 5 字节；加 cc(2)+\n(1) 到 8 字节超限 → 停在 bb
        assertEquals("aa\nbb", result.content)
    }

    @Test
    fun head_keepsMultibyteLinesByBytes() {
        // 多字节行按 UTF-8 字节计数，边界落在整行之间，不切半个字符
        val content = "a\n中文\nb" // 1 + 1 + 6 + 1 + 1 = 10 字节
        val result = ToolOutputTruncator.truncateHead(content, maxLines = 100, maxBytes = 9)

        assertTrue(result.truncated)
        // a(1) + 中文(6+1) = 8 字节 ≤ 9；再加 b(1+1) 到 10 超限 → 停在中文行
        assertEquals("a\n中文", result.content)
    }

    @Test
    fun tail_overLineLimit_keepsLastLines() {
        val content = (1..2500).joinToString("\n") { "line $it" }
        val result = ToolOutputTruncator.truncateTail(content, maxLines = 10)

        assertTrue(result.truncated)
        assertEquals(2500, result.totalLines)
        assertEquals(
            (2491..2500).joinToString("\n") { "line $it" },
            result.content,
        )
    }

    @Test
    fun tail_overByteLimit_keepsTailCompleteLines() {
        val content = "aa\nbb\ncc\ndd"
        val result = ToolOutputTruncator.truncateTail(content, maxLines = 100, maxBytes = 7)

        assertTrue(result.truncated)
        assertEquals("cc\ndd", result.content)
    }

    @Test
    fun tail_singleHugeLine_takesPartialEnd() {
        val content = "x".repeat(100)
        val result = ToolOutputTruncator.truncateTail(content, maxLines = 100, maxBytes = 10)

        assertTrue(result.truncated)
        assertEquals("x".repeat(10), result.content)
    }

    @Test
    fun tail_neverCutsMultibyteChar() {
        val content = "第一行\n第二行"
        val result = ToolOutputTruncator.truncateTail(content, maxLines = 100, maxBytes = 10)

        assertTrue(result.truncated)
        // 尾部 10 字节 = 第二行（3字×3字节+换行1字节=10字节），边界不切半个字符
        assertEquals("第二行", result.content)
    }
}
