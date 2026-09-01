package com.niki914.zafiro.app.ui.content.reveal

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealTimelineTest {

    @Test
    fun `uses base speed floor for tiny backlog`() {
        // 积压 7 字素时追赶曲线低于基速，走基速：36 * 0.05s = 1.8
        val advanced = RevealTimeline.advance(current = 0f, target = 7f, elapsedSeconds = 0.05f)
        assertEquals(
            RevealTimeline.BASE_CHARS_PER_SECOND * RevealTimeline.MAX_FRAME_SECONDS,
            advanced,
            0.01f
        )
    }

    @Test
    fun `catches up when backlog builds and stays under cap`() {
        // 积压 2000 字素：追赶速度远超上限，单帧步长 = 240 * 0.05s = 12
        val advanced = RevealTimeline.advance(current = 0f, target = 2000f, elapsedSeconds = 0.05f)
        assertEquals(
            RevealTimeline.MAX_CHARS_PER_SECOND * RevealTimeline.MAX_FRAME_SECONDS,
            advanced,
            0.01f
        )
    }

    @Test
    fun `never overshoots target`() {
        val advanced = RevealTimeline.advance(current = 99.9f, target = 100f, elapsedSeconds = 1f)
        assertEquals(100f, advanced, 0.001f)
    }

    @Test
    fun `long gap advances at most one capped frame`() {
        val advanced = RevealTimeline.advance(current = 0f, target = 2000f, elapsedSeconds = 10f)
        assertEquals(
            RevealTimeline.MAX_CHARS_PER_SECOND * RevealTimeline.MAX_FRAME_SECONDS,
            advanced,
            0.01f,
        )
    }

    @Test
    fun `no progress when complete`() {
        val advanced = RevealTimeline.advance(current = 100f, target = 100f, elapsedSeconds = 1f)
        assertEquals(100f, advanced, 0.001f)
    }

    @Test
    fun `target merges monotonically across blocks`() {
        val timeline = RevealTimeline()
        timeline.mergeTarget(100) // 第一段文本块
        timeline.mergeTarget(40)  // 后续块出现时不可回缩，否则已打出的字会被吞掉
        assertEquals(100, timeline.targetChars)
        timeline.mergeTarget(260) // 更大的块继续推进
        assertEquals(260, timeline.targetChars)
    }
}
