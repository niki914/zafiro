package com.niki914.zafiro.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissZoneTest {
    @Test
    fun insideTheRadiusCounts() {
        assertTrue(DismissZone.isOver(500f, 1900f, 540f, 1900f, 100f))
        assertTrue(DismissZone.isOver(540f, 1900f, 540f, 1900f, 1f))
    }

    @Test
    fun outsideTheRadiusDoesNot() {
        assertFalse(DismissZone.isOver(100f, 1900f, 540f, 1900f, 100f))
        assertFalse(DismissZone.isOver(540f, 1500f, 540f, 1900f, 100f))
    }
}
