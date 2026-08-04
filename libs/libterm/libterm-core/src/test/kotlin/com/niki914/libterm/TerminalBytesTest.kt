package com.niki914.libterm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalBytesTest {

    @Test
    fun `of copies input bytes`() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = TerminalBytes.of(source)

        source[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
    }

    @Test
    fun `toByteArray returns copy`() {
        val bytes = TerminalBytes.of(byteArrayOf(1, 2, 3))

        val exported = bytes.toByteArray()
        exported[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
    }

    @Test
    fun `equals uses byte content`() {
        val first = TerminalBytes.of(byteArrayOf(0x1B, 0x5B, 0x31, 0x6D))
        val second = TerminalBytes.of(byteArrayOf(0x1B, 0x5B, 0x31, 0x6D))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `empty has zero size`() {
        assertEquals(0, TerminalBytes.EMPTY.size)
        assertTrue(TerminalBytes.EMPTY.isEmpty)
        assertFalse(TerminalBytes.of(byteArrayOf(1)).isEmpty)
    }
}
