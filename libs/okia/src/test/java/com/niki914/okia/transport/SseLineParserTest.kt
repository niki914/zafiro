package com.niki914.okia.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 行切分测试：任何分块方式下切行与 W3C SSE 标准一致。
 * 真实 HTTP 字节块边界任意，跨块是主要风险。
 */
class SseLineParserTest {

    private val parser = SseLineParser()

    private suspend fun parse(vararg chunks: String): List<SseLine> =
        parser.parse(chunks.toList().asFlow()).toList()

    private fun chunks(vararg chunks: String): Flow<String> = chunks.toList().asFlow()

    // ── 分隔符 ───────────────────────────────────────────────────────────

    @Test
    fun splitsOnLf() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("b")), parse("a\nb\n"))
    }

    @Test
    fun splitsOnCrlf() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("b")), parse("a\r\nb\r\n"))
    }

    @Test
    fun splitsOnCr() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("b")), parse("a\rb\r"))
    }

    @Test
    fun mixedDelimiters() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("b"), SseLine("c")), parse("a\rb\nc\r\n"))
    }

    @Test
    fun crlfInSameBlockIsOneDelimiter() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("")), parse("a\r\n\r\n"))
    }

    // ── 跨块 ─────────────────────────────────────────────────────────────

    @Test
    fun blockBoundaryAtLf() = runTest {
        assertEquals(listOf(SseLine("ab"), SseLine("cd")), parse("ab", "\ncd\n"))
    }

    @Test
    fun blockBoundaryAtCr() = runTest {
        assertEquals(listOf(SseLine("ab"), SseLine("cd")), parse("ab", "\rcd\r"))
    }

    @Test
    fun blockBoundaryInMiddleOfLine() = runTest {
        assertEquals(listOf(SseLine("abcd"), SseLine("ef")), parse("ab", "cd", "\nef\n"))
    }

    @Test
    fun crlfAcrossBlocks() = runTest {
        // "ab\r" 跨块 + "\ncd"：\r\n 是一个分隔符，只产生 "ab" 一行
        assertEquals(listOf(SseLine("ab"), SseLine("cd")), parse("ab\r", "\ncd\n"))
    }

    @Test
    fun crAtBlockEndNotFollowedByLf() = runTest {
        // "ab\r" 跨块但下一块不以 \n 开头：\r 单独作分隔符
        assertEquals(listOf(SseLine("ab"), SseLine("cd")), parse("ab\r", "cd\n"))
    }

    @Test
    fun oneCharPerBlock() = runTest {
        assertEquals(
            listOf(SseLine("abc"), SseLine("def")),
            parse("a", "b", "c", "\n", "d", "e", "f", "\n")
        )
    }

    @Test
    fun trailingCrAtEofDoesNotCreatePendingLine() = runTest {
        assertEquals(listOf(SseLine("ab")), parse("ab\r"))
    }

    // ── EOF flush ────────────────────────────────────────────────────────

    @Test
    fun flushLastLineWithoutTrailingNewline() = runTest {
        assertEquals(listOf(SseLine("a"), SseLine("b")), parse("a\nb"))
    }

    @Test
    fun trailingNewlineProducesNoExtraLine() = runTest {
        assertEquals(listOf(SseLine("a")), parse("a\n"))
    }

    @Test
    fun trailingCrlfProducesNoExtraLine() = runTest {
        assertEquals(listOf(SseLine("a")), parse("a\r\n"))
    }

    // ── 行分类 ───────────────────────────────────────────────────────────

    @Test
    fun commentLineIsNull() = runTest {
        assertEquals(listOf(SseLine(null), SseLine("data: x")), parse(": keep-alive\ndata: x\n"))
    }

    @Test
    fun commentLineWithoutSpaceIsNull() = runTest {
        assertEquals(listOf(SseLine(null)), parse(":ping\n"))
    }

    @Test
    fun emptyLineIsEmptyString() = runTest {
        assertEquals(listOf(SseLine("")), parse("\n"))
    }

    @Test
    fun consecutiveEmptyLines() = runTest {
        assertEquals(listOf(SseLine(""), SseLine("")), parse("\n\n"))
    }

    @Test
    fun ordinaryLineKeepsOriginalText() = runTest {
        assertEquals(listOf(SseLine("data: a:b")), parse("data: a:b\n"))
    }

    // ── BOM ──────────────────────────────────────────────────────────────

    @Test
    fun bomAtStreamStartRemoved() = runTest {
        assertEquals(listOf(SseLine("data: x")), parse("\uFEFFdata: x\n"))
    }

    @Test
    fun bomInLaterBlockPreserved() = runTest {
        assertEquals(
            listOf(SseLine("data: a"), SseLine("\uFEFFdata: b")),
            parse("data: a\n", "\uFEFFdata: b\n")
        )
    }

    // ── 输入边界 ─────────────────────────────────────────────────────────

    @Test
    fun emptyInputProducesNoLines() = runTest {
        assertEquals(emptyList<SseLine>(), parse())
    }

    @Test
    fun emptyChunkProducesNothing() = runTest {
        assertEquals(listOf(SseLine("a")), parse("", "a", "", "\n", ""))
    }

    @Test
    fun longLineNotTruncated() = runTest {
        val long = "data: " + "x".repeat(100_000)
        assertEquals(listOf(SseLine(long)), parse(long + "\n"))
    }

    // ── 流语义 ───────────────────────────────────────────────────────────

    @Test
    fun coldFlowIsolation() = runTest {
        // 同一 Flow 两次 collect：切分状态不跨 collect 泄漏
        val stream = parser.parse(chunks("a\nb\n"))
        assertEquals(listOf(SseLine("a"), SseLine("b")), stream.toList())
        assertEquals(listOf(SseLine("a"), SseLine("b")), stream.toList())
    }

    @Test
    fun upstreamExceptionPropagates() = runTest {
        val boom = RuntimeException("boom")
        val upstream = flow<String> {
            emit("a\n")
            throw boom
        }
        val caught = runCatching { parser.parse(upstream).toList() }.exceptionOrNull()
        assertTrue(caught === boom)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val stream = parser.parse(flow { emit("a"); kotlinx.coroutines.delay(10_000) })
        var caught: CancellationException? = null
        val job = launch {
            try {
                stream.toList()
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(caught != null)
    }
}
