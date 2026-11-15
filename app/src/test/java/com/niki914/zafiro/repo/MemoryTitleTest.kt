package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTitleTest {
    @Test fun labelBeforeColonBecomesTheTitle() {
        assertEquals("Device", MemoryTitle.of("Device: TECNO POP 7 Pro, Android 12, not rooted"))
        assertEquals("OWNER RULE (2026-11-13)", MemoryTitle.of("OWNER RULE (2026-11-13): never use any Opus model"))
    }

    @Test fun otherwiseTheFirstSentenceIsShortened() {
        val t = MemoryTitle.of("Standing rules persist until the task is done and find workarounds. Keep a journal.")
        assertTrue(t, t.length <= 46 && t.endsWith("…") && t.startsWith("Standing rules"))
        assertEquals("Short one", MemoryTitle.of("short one."))
        assertEquals("(empty)", MemoryTitle.of("  "))
    }
}
