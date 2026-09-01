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
 * T3 事件聚合测试：事件语义与 W3C SSE 标准一致。
 * 输入是行切分后的 SseLine 流（\r\n 已由 SseLineParser 归一化）。
 */
class SseEventParserTest {

    private val parser = SseEventParser()

    private fun line(text: String) = SseLine(text)
    private fun comment() = SseLine(null)
    private fun blank() = SseLine("")

    private fun lines(vararg text: SseLine): Flow<SseLine> = text.toList().asFlow()

    private suspend fun parse(vararg text: SseLine): List<SseEvent> =
        parser.parse(text.toList().asFlow()).toList()

    // ── data 聚合 ────────────────────────────────────────────────────────

    @Test
    fun singleEventSingleLine() = runTest {
        assertEquals(listOf(SseEvent("hello")), parse(line("data: hello"), blank()))
    }

    @Test
    fun multiLineDataJoinedWithNewline() = runTest {
        assertEquals(
            listOf(SseEvent("a\nb")),
            parse(line("data: a"), line("data: b"), blank())
        )
    }

    @Test
    fun twoEventsSeparatedByBlankLine() = runTest {
        assertEquals(
            listOf(SseEvent("a"), SseEvent("b")),
            parse(line("data: a"), blank(), line("data: b"), blank())
        )
    }

    @Test
    fun emptyDataEventDropped() = runTest {
        // W3C：data 缓冲为空字符串的事件不 dispatch
        assertEquals(emptyList<SseEvent>(), parse(line("data:"), blank()))
    }

    @Test
    fun whitespaceDataKept() = runTest {
        // "data:  x" 移除一个前导空格 → " x"；纯空白值非空字符串，保留
        assertEquals(listOf(SseEvent(" x")), parse(line("data:  x"), blank()))
        assertEquals(listOf(SseEvent(" ")), parse(line("data:  "), blank()))
    }

    @Test
    fun consecutiveBlankLinesDoNotProduceExtraEvents() = runTest {
        assertEquals(
            listOf(SseEvent("a")),
            parse(line("data: a"), blank(), blank(), blank())
        )
    }

    @Test
    fun commentLinesSkipped() = runTest {
        // keep-alive 注释行不产生事件（在行流中保留供 idle 检测）
        assertEquals(
            listOf(SseEvent("a")),
            parse(comment(), line(": keep-alive"), line("data: a"), blank())
        )
    }

    @Test
    fun commentBetweenDataAndBlankDoesNotSplitEvent() = runTest {
        assertEquals(
            listOf(SseEvent("a")),
            parse(line("data: a"), comment(), blank())
        )
    }

    // ── 事件边界 / EOF ───────────────────────────────────────────────────

    @Test
    fun eofFlushPendingEvent() = runTest {
        // 无尾空行：挂起事件在流结束时 dispatch（pi codex 版漏掉的 case）
        assertEquals(listOf(SseEvent("a\nb")), parse(line("data: a"), line("data: b")))
    }

    @Test
    fun eofWithOnlyCommentProducesNothing() = runTest {
        assertEquals(emptyList<SseEvent>(), parse(comment()))
    }

    // ── event 字段 ───────────────────────────────────────────────────────

    @Test
    fun eventFieldPassedThrough() = runTest {
        assertEquals(
            listOf(SseEvent("x", "message")),
            parse(line("event: message"), line("data: x"), blank())
        )
    }

    @Test
    fun eventWithoutDataDropped() = runTest {
        // 只有 event 无 data：data 缓冲为空，丢弃
        assertEquals(emptyList<SseEvent>(), parse(line("event: ping"), blank()))
    }

    @Test
    fun eventTypeResetBetweenEvents() = runTest {
        // event 字段不跨事件泄漏
        assertEquals(
            listOf(SseEvent("a", "x"), SseEvent("b", null)),
            parse(line("event: x"), line("data: a"), blank(), line("data: b"), blank())
        )
    }

    @Test
    fun fieldOrderIndependent() = runTest {
        // data 在 event 前
        assertEquals(
            listOf(SseEvent("a", "x")),
            parse(line("data: a"), line("event: x"), blank())
        )
    }

    @Test
    fun emptyEventValuePassedThrough() = runTest {
        assertEquals(
            listOf(SseEvent("a", "")),
            parse(line("event:"), line("data: a"), blank())
        )
    }

    // ── 字段细节 ─────────────────────────────────────────────────────────

    @Test
    fun unknownFieldIgnored() = runTest {
        assertEquals(
            listOf(SseEvent("a")),
            parse(line("foo: bar"), line("data: a"), blank())
        )
    }

    @Test
    fun lineWithoutColonIgnored() = runTest {
        assertEquals(
            listOf(SseEvent("a")),
            parse(line("bare line"), line("data: a"), blank())
        )
    }

    @Test
    fun colonInsideValuePreserved() = runTest {
        assertEquals(listOf(SseEvent("a:b")), parse(line("data: a:b"), blank()))
    }

    @Test
    fun singleLeadingSpaceStripped() = runTest {
        // "data: x" 冒号后一个空格被移除 → "x"
        assertEquals(listOf(SseEvent("x")), parse(line("data: x"), blank()))
    }

    @Test
    fun idAndRetryIgnored() = runTest {
        assertEquals(
            listOf(SseEvent("a")),
            parse(line("id: 1"), line("retry: 1000"), line("data: a"), blank())
        )
    }

    @Test
    fun dataValueWithTrailingBlankDataLine() = runTest {
        // "data: a" + "data:"（空值）→ "a" + "\n" + "" = "a\n"（W3C 拼接规则）
        assertEquals(
            listOf(SseEvent("a\n")),
            parse(line("data: a"), line("data:"), blank())
        )
    }

    // ── 流语义 ───────────────────────────────────────────────────────────

    @Test
    fun coldFlowIsolation() = runTest {
        val stream = parser.parse(lines(line("data: a"), blank()))
        assertEquals(listOf(SseEvent("a")), stream.toList())
        assertEquals(listOf(SseEvent("a")), stream.toList())
    }

    @Test
    fun upstreamExceptionPropagates() = runTest {
        val boom = RuntimeException("boom")
        val upstream = flow<SseLine> {
            emit(line("data: a"))
            throw boom
        }
        val caught = runCatching { parser.parse(upstream).toList() }.exceptionOrNull()
        assertTrue(caught === boom)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val stream = parser.parse(flow {
            emit(line("data: a"))
            kotlinx.coroutines.delay(10_000)
        })
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
