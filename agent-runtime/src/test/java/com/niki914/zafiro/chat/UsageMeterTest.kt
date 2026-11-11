package com.niki914.zafiro.chat

import com.niki914.okia.message.Usage
import com.niki914.zafiro.api.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMeterTest {
    @Test
    fun sumsAcrossRequests() {
        val meter = UsageMeter()
        meter.ingest(
            listOf(
                "a" to Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 500, cacheWriteTokens = 10),
                "b" to Usage(inputTokens = 50, outputTokens = 5),
            )
        )
        val t = meter.totals.value
        assertEquals(2, t.requests)
        assertEquals(150, t.inputTokens)
        assertEquals(25, t.outputTokens)
        assertEquals(500, t.cacheReadTokens)
        assertEquals(10, t.cacheWriteTokens)
    }

    @Test
    fun sameEntryIsNeverCountedTwice() {
        val meter = UsageMeter()
        val entries = listOf("a" to Usage(inputTokens = 100, outputTokens = 10))
        meter.ingest(entries)
        meter.ingest(entries)
        meter.ingest(entries + ("b" to Usage(inputTokens = 1)))
        assertEquals(2, meter.totals.value.requests)
        assertEquals(111, meter.totals.value.billable)
    }

    @Test
    fun entriesDroppedByCompactionStayCounted() {
        val meter = UsageMeter()
        meter.ingest(listOf("a" to Usage(inputTokens = 100), "b" to Usage(inputTokens = 200)))
        meter.ingest(listOf("summary" to null, "c" to Usage(inputTokens = 5)))
        assertEquals(305, meter.totals.value.billable)
    }

    @Test
    fun billableExcludesCacheReads() {
        val meter = UsageMeter()
        meter.ingest(listOf("a" to Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 9_000, cacheWriteTokens = 30)))
        assertEquals(150, meter.totals.value.billable)
    }

    @Test
    fun negativeValuesAreClamped() {
        val meter = UsageMeter()
        meter.ingest(listOf("a" to Usage(inputTokens = -5, outputTokens = 7)))
        assertEquals(7, meter.totals.value.billable)
    }

    @Test
    fun resetClears() {
        val meter = UsageMeter()
        meter.ingest(listOf("a" to Usage(inputTokens = 1)))
        meter.reset()
        assertEquals(TokenUsage(), meter.totals.value)
        meter.ingest(listOf("a" to Usage(inputTokens = 1)))
        assertEquals(1, meter.totals.value.requests)
    }
}
