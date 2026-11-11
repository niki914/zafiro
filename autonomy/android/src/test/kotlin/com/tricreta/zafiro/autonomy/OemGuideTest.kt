package com.tricreta.zafiro.autonomy

import com.tricreta.zafiro.autonomy.reliability.OemGuide
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OemGuideTest {
    @Test
    fun `tecno is recognised by manufacturer or brand and mentions locking the recents card`() {
        for ((m, b) in listOf("TECNO" to "TECNO", "Transsion Holdings" to "TECNO", "INFINIX" to "Infinix", "itel" to "itel")) {
            val a = OemGuide.adviceFor(m, b)
            assertTrue(a.vendorLabel.contains("HiOS"), "$m/$b -> ${a.vendorLabel}")
            assertTrue(a.steps.any { it.contains("LOCK", ignoreCase = true) })
            assertEquals("com.transsion.phonemaster", a.managerPackage)
        }
    }

    @Test
    fun `samsung points at never sleeping apps`() {
        val a = OemGuide.adviceFor("samsung", "samsung")
        assertTrue(a.steps.any { it.contains("Never sleeping apps") })
        assertEquals("com.samsung.android.lool", a.managerPackage)
    }

    @Test
    fun `unknown brands still get the generic steps and no vendor app`() {
        val a = OemGuide.adviceFor("Google", "google")
        assertTrue(a.steps.isNotEmpty())
        assertNull(a.managerPackage)
    }
}
