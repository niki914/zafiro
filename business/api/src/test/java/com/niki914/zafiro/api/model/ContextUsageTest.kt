// 保护：占用比率的除零与越界——窗口为 0 时必须是 0f 而不是 NaN（Float 的 NaN 过 coerceIn 拦不住）
package com.niki914.zafiro.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextUsageTest {

    @Test
    fun `non positive window yields zero progress instead of NaN`() {
        val progress = ContextUsage(usedTokens = 1_000L, windowTokens = 0L).progress

        assertFalse(progress.isNaN())
        assertEquals(0f, progress)
    }

    @Test
    fun `progress is the ratio while within the window`() {
        assertEquals(
            0.25f,
            ContextUsage(usedTokens = 50_000L, windowTokens = 200_000L).progress,
        )
    }

    @Test
    fun `progress clamps when usage exceeds the window`() {
        assertEquals(
            1f,
            ContextUsage(usedTokens = 300_000L, windowTokens = 200_000L).progress,
        )
    }
}
