package com.niki914.zafiro.app.screen

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：可信旗标被拒绝后如果还带着 TRUSTED 或 OWN_FOCUS，建屏会直接失败，用户打不开虚拟屏。
class ShizukuDisplayFlagsTest {
    private val flagPublic = 1 shl 0
    private val flagOwnContentOnly = 1 shl 3
    private val flagSupportsTouch = 1 shl 6
    private val flagRotatesWithContent = 1 shl 7
    private val flagDestroyContentOnRemoval = 1 shl 8
    private val flagTrusted = 1 shl 10
    private val flagOwnDisplayGroup = 1 shl 11
    private val flagAlwaysUnlocked = 1 shl 12
    private val flagTouchFeedbackDisabled = 1 shl 13
    private val flagOwnFocus = 1 shl 14
    private val flagStealTopFocusDisabled = 1 shl 16

    private val baseFlags = flagPublic or flagOwnContentOnly or flagSupportsTouch or
        flagRotatesWithContent or flagDestroyContentOnRemoval

    @Test
    fun android11KeepsBaseFlagsOnly() {
        val flags = computeDisplayFlags(Build.VERSION_CODES.R, trusted = true)
        assertEquals(baseFlags, flags)
        assertFalse(has(flags, flagTrusted))
        assertFalse(has(flags, flagOwnFocus))
    }

    @Test
    fun android13TrustedAddsTrustedGroup() {
        val flags = computeDisplayFlags(Build.VERSION_CODES.TIRAMISU, trusted = true)
        assertTrue(has(flags, baseFlags))
        assertTrue(has(flags, flagTouchFeedbackDisabled))
        assertTrue(has(flags, flagTrusted))
        assertTrue(has(flags, flagOwnDisplayGroup))
        assertTrue(has(flags, flagAlwaysUnlocked))
        assertFalse(has(flags, flagOwnFocus))
        assertFalse(has(flags, flagStealTopFocusDisabled))
    }

    @Test
    fun android13FallbackDropsTrustedGroup() {
        val flags = computeDisplayFlags(Build.VERSION_CODES.TIRAMISU, trusted = false)
        assertTrue(has(flags, baseFlags))
        assertTrue(has(flags, flagTouchFeedbackDisabled))
        assertFalse(has(flags, flagTrusted))
        assertFalse(has(flags, flagOwnDisplayGroup))
        assertFalse(has(flags, flagAlwaysUnlocked))
        assertFalse(has(flags, flagOwnFocus))
    }

    @Test
    fun android14TrustedAddsFocusIsolation() {
        val flags = computeDisplayFlags(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, trusted = true)
        assertTrue(has(flags, flagTrusted))
        assertTrue(has(flags, flagOwnFocus))
        assertTrue(has(flags, flagStealTopFocusDisabled))
    }

    @Test
    fun android14FallbackDropsFocusIsolation() {
        val flags = computeDisplayFlags(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, trusted = false)
        assertTrue(has(flags, flagTouchFeedbackDisabled))
        assertFalse(has(flags, flagTrusted))
        assertFalse(has(flags, flagOwnFocus))
        assertFalse(has(flags, flagStealTopFocusDisabled))
    }

    private fun has(value: Int, flag: Int): Boolean = value and flag == flag
}
