package com.niki914.okia.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingLevelTest {

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

}
