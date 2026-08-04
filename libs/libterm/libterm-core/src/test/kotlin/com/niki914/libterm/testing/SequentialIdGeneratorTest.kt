package com.niki914.libterm.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SequentialIdGeneratorTest {

    @Test
    fun `next id returns deterministic incrementing sequence`() {
        val generator = SequentialIdGenerator(prefix = "session-", start = 3L)

        val ids = listOf(
            generator.nextId(),
            generator.nextId(),
            generator.nextId(),
        )

        assertEquals(listOf("session-3", "session-4", "session-5"), ids)
    }

    @Test
    fun `negative start is rejected immediately`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SequentialIdGenerator(start = -1L)
        }

        assertEquals("start must be >= 0", error.message)
    }

    @Test
    fun `fake clock supports explicit set and advance for deterministic examples`() {
        val clock = FakeClock(initialMillis = 10L)

        clock.setNowMillis(42L)
        val advancedMillis = clock.advanceBy(8L)

        assertEquals(50L, advancedMillis)
        assertEquals(50L, clock.nowMillis())
    }

    @Test
    fun `fake clock rejects negative advance`() {
        val clock = FakeClock()

        val error = assertFailsWith<IllegalArgumentException> {
            clock.advanceBy(-1L)
        }

        assertEquals("deltaMillis must be >= 0", error.message)
    }
}
