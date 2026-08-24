package com.niki914.zafiro.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ActiveTurnStoreTest {

    @Before
    fun setUp() {
        ActiveTurnStore.clear()
    }

    @After
    fun tearDown() {
        ActiveTurnStore.clear()
    }

    @Test
    fun getCurrent_returnsNullByDefault() {
        assertNull(ActiveTurnStore.getCurrent())
        assertFalse(ActiveTurnStore.hasActiveTurn())
        assertFalse(ActiveTurnStore.isCurrentInjected())
        assertFalse(ActiveTurnStore.isActiveInjection(1L))
    }

    @Test
    fun setCurrent_makesStateReadable() {
        val state = ConversationTurnState(
            turnId = 1L,
            lastQuery = "hello",
            mode = TurnMode.InjectedLLM,
        )

        ActiveTurnStore.setCurrent(state)

        assertEquals(state, ActiveTurnStore.getCurrent())
        assertTrue(ActiveTurnStore.hasActiveTurn())
    }

    @Test
    fun clear_removesSingleActiveTurn() {
        ActiveTurnStore.setCurrent(
            ConversationTurnState(
                turnId = 1L,
                lastQuery = "hello",
                mode = TurnMode.InjectedLLM,
            )
        )

        ActiveTurnStore.clear()

        assertNull(ActiveTurnStore.getCurrent())
        assertFalse(ActiveTurnStore.hasActiveTurn())
    }

    @Test
    fun isCurrentInjected_returnsTrueOnlyForInjectedMode() {
        ActiveTurnStore.setCurrent(
            ConversationTurnState(
                turnId = 1L,
                lastQuery = "hello",
                mode = TurnMode.InjectedLLM,
            )
        )

        assertTrue(ActiveTurnStore.isCurrentInjected())

        ActiveTurnStore.setCurrent(
            ConversationTurnState(
                turnId = 2L,
                lastQuery = "takeover",
                mode = TurnMode.NativeTakeover,
            )
        )

        assertFalse(ActiveTurnStore.isCurrentInjected())
    }

    @Test
    fun isActiveInjection_trueOnlyWhenTurnIdMatchesAndModeInjected() {
        ActiveTurnStore.setCurrent(
            ConversationTurnState(
                turnId = 42L,
                lastQuery = "hello",
                mode = TurnMode.InjectedLLM,
            )
        )

        assertTrue(ActiveTurnStore.isActiveInjection(42L))
        assertFalse(ActiveTurnStore.isActiveInjection(41L))

        ActiveTurnStore.setCurrent(
            ConversationTurnState(
                turnId = 42L,
                lastQuery = "takeover",
                mode = TurnMode.NativeTakeover,
            )
        )

        assertFalse(ActiveTurnStore.isActiveInjection(42L))
    }

    @Test
    fun isActiveInjection_falseWhenNoActiveTurn() {
        assertFalse(ActiveTurnStore.isActiveInjection(42L))
    }

    @Test
    fun setCurrent_overwritesPreviousState() {
        val first = ConversationTurnState(
            turnId = 1L,
            lastQuery = "first",
            mode = TurnMode.InjectedLLM,
        )
        val second = ConversationTurnState(
            turnId = 2L,
            lastQuery = "second",
            mode = TurnMode.NativeTakeover,
        )

        ActiveTurnStore.setCurrent(first)
        ActiveTurnStore.setCurrent(second)

        assertEquals(second, ActiveTurnStore.getCurrent())
    }

    @Test
    fun setCurrent_acceptsBlankQuery() {
        val state = ConversationTurnState(
            turnId = 1L,
            lastQuery = "",
            mode = TurnMode.InjectedLLM,
        )

        ActiveTurnStore.setCurrent(state)

        assertEquals(state, ActiveTurnStore.getCurrent())
        assertEquals("", ActiveTurnStore.getCurrent()?.lastQuery)
    }

    @Test
    fun concurrentSetAndGet_doesNotThrow() {
        val executor = Executors.newFixedThreadPool(4)
        val futures = (0 until 4).map { worker ->
            executor.submit {
                repeat(100) { index ->
                    ActiveTurnStore.setCurrent(
                        ConversationTurnState(
                            turnId = index.toLong(),
                            lastQuery = "query-$index",
                            mode = if (index % 2 == 0) {
                                TurnMode.InjectedLLM
                            } else {
                                TurnMode.NativeTakeover
                            },
                        )
                    )
                    ActiveTurnStore.getCurrent()
                    ActiveTurnStore.hasActiveTurn()
                }
            }
        }

        try {
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(ActiveTurnStore.hasActiveTurn())
        ActiveTurnStore.clear()
        assertNull(ActiveTurnStore.getCurrent())
    }
}
