package com.niki914.okia.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingLevelTest {

    @Test
    fun fromWireParsesAllLevels() {
        ThinkingLevel.entries.forEach { level ->
            assertEquals(level, ThinkingLevel.fromWire(level.wireValue))
        }
    }

    @Test
    fun fromWireIsCaseInsensitive() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromWire("HIGH"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.fromWire(" XHigh "))
    }

    @Test
    fun fromWireMapsAliases() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromWire("off"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromWire("disabled"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.fromWire("x-high"))
    }

    @Test
    fun fromWireFallsBackToDefaultOnUnknown() {
        assertEquals(ThinkingLevel.Default, ThinkingLevel.fromWire("ultra"))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.fromWire(null))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.fromWire(""))
    }

    @Test
    fun requestsThinkingExcludesOff() {
        assertEquals(false, ThinkingLevel.OFF.requestsThinking)
        ThinkingLevel.entries.filter { it != ThinkingLevel.OFF }.forEach { level ->
            assertEquals(true, level.requestsThinking)
        }
    }
}
