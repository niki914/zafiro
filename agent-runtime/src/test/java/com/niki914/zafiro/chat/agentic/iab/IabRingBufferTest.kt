package com.niki914.zafiro.chat.agentic.iab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IabRingBuffer] 淘汰顺序与快照语义的 JVM 单测。 */
class IabRingBufferTest {

    @Test
    fun evictsOldestWhenFull() {
        val ring = IabRingBuffer<String>(3)
        listOf("a", "b", "c", "d", "e").forEach { ring.add(it) }
        assertEquals(listOf("c", "d", "e"), ring.snapshot())
        assertEquals(3, ring.size)
    }

    @Test
    fun snapshotLimitTakesNewest() {
        val ring = IabRingBuffer<Int>(5)
        (1..5).forEach { ring.add(it) }
        assertEquals(listOf(3, 4, 5), ring.snapshot(3))
        assertEquals(listOf(1, 2, 3, 4, 5), ring.snapshot(null))
        assertEquals(emptyList<Int>(), ring.snapshot(0))
    }

    @Test
    fun limitLargerThanCapacityIsClamped() {
        val ring = IabRingBuffer<Int>(2)
        ring.add(1)
        ring.add(2)
        ring.add(3)
        assertEquals(2, ring.snapshot(100).size)
    }

    @Test
    fun capacityMustBePositive() {
        try {
            IabRingBuffer<String>(0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun networkEntrySnapshotIsStableCopy() {
        val ring = IabRingBuffer<IabNetworkEntry>(10)
        val e = IabNetworkEntry(atMs = 1L, method = "GET", url = "https://x", mime = null, note = null)
        ring.add(e)
        assertEquals(1, ring.snapshot().size)
        assertTrue(ring.snapshot().first().url == "https://x")
    }
}
