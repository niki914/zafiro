package com.niki914.zafiro.app.ui.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPacerTest {

    @Test
    fun `base speed floor for tiny backlog`() {
        val advanced = TextPacer.advance(current = 0f, target = 7f, elapsedSeconds = 0.05f)
        assertEquals(
            TextPacer.BASE_CHARS_PER_SECOND * TextPacer.MAX_FRAME_SECONDS,
            advanced,
            0.01f,
        )
    }

    @Test
    fun `catches up when backlog builds and stays under cap`() {
        val advanced = TextPacer.advance(current = 0f, target = 2000f, elapsedSeconds = 0.05f)
        assertEquals(
            TextPacer.MAX_CHARS_PER_SECOND * TextPacer.MAX_FRAME_SECONDS,
            advanced,
            0.01f,
        )
    }

    @Test
    fun `never overshoots target`() {
        val advanced = TextPacer.advance(current = 99.9f, target = 100f, elapsedSeconds = 1f)
        assertEquals(100f, advanced, 0.001f)
    }

    @Test
    fun `no progress when complete`() {
        val advanced = TextPacer.advance(current = 100f, target = 100f, elapsedSeconds = 1f)
        assertEquals(100f, advanced, 0.001f)
    }

    @Test
    fun `pace releases contiguous chunks covering full range`() = runTest {
        val pacer = TextPacer(delayFn = { })
        val chunks = mutableListOf<Pair<Int, Int>>()
        pacer.pace(targetChars = 20) { from, to -> chunks += from to to }
        assertEquals(0, chunks.first().first)
        assertEquals(20, chunks.last().second)
        // 区间连续无缝
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
        assertEquals(20, pacer.released)
    }

    @Test
    fun `pace below released is a no-op`() = runTest {
        val pacer = TextPacer(delayFn = { })
        var calls = 0
        pacer.pace(10) { _, _ -> calls++ }
        assertEquals(10, pacer.released)
        val callsAfterFirst = calls
        pacer.pace(5) { _, _ -> calls++ }
        assertEquals(10, pacer.released)
        assertEquals(callsAfterFirst, calls)
    }

    @Test
    fun `stall snap releases remainder in one chunk`() = runTest {
        val pacer = TextPacer(delayFn = { })
        // 模拟上游停顿：首段放出后等待真实时间超过 STALL_SNAP_MS
        pacer.pace(4) { _, _ -> }
        Thread.sleep(TextPacer.STALL_SNAP_MS + 20)
        val chunks = mutableListOf<Pair<Int, Int>>()
        pacer.pace(400) { from, to -> chunks += from to to }
        assertEquals(1, chunks.size)
        assertEquals(400, pacer.released)
    }

    @Test
    fun `reset starts a new segment from zero`() = runTest {
        val pacer = TextPacer(delayFn = { })
        pacer.pace(10) { _, _ -> }
        pacer.reset()
        assertEquals(0, pacer.released)
        val chunks = mutableListOf<Pair<Int, Int>>()
        pacer.pace(5) { from, to -> chunks += from to to }
        assertEquals(0, chunks.first().first)
        assertEquals(5, pacer.released)
    }
}
