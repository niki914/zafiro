package com.tricreta.zafiro.autonomy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunStateAndBudgetTest {
    @Test
    fun `terminal states have no outgoing transitions`() {
        for (s in RunState.entries.filter { it.terminal }) {
            for (to in RunState.entries) assertFalse(RunStateMachine.canTransition(s, to), "$s -> $to")
        }
    }

    @Test
    fun `happy path and pause resume are allowed`() {
        assertTrue(RunStateMachine.canTransition(RunState.QUEUED, RunState.STARTING))
        assertTrue(RunStateMachine.canTransition(RunState.STARTING, RunState.RUNNING))
        assertTrue(RunStateMachine.canTransition(RunState.RUNNING, RunState.PAUSED))
        assertTrue(RunStateMachine.canTransition(RunState.PAUSED, RunState.RUNNING))
        assertTrue(RunStateMachine.canTransition(RunState.RUNNING, RunState.SUCCEEDED))
    }

    @Test
    fun `cannot skip straight from queued to succeeded`() {
        assertFalse(RunStateMachine.canTransition(RunState.QUEUED, RunState.SUCCEEDED))
        assertFailsWith<IllegalStateException> { RunStateMachine.require(RunState.QUEUED, RunState.SUCCEEDED) }
    }

    @Test
    fun `MISSED is created terminal and never transitions`() {
        assertTrue(RunState.MISSED.terminal)
        assertFalse(RunStateMachine.canTransition(RunState.QUEUED, RunState.MISSED))
    }

    @Test
    fun `step budget refuses once the limit is reached`() {
        val t = BudgetTracker(Budget(maxSteps = 3, maxDurationMs = null, maxTokens = null), 0)
        repeat(2) { t.recordStep() }
        assertNull(t.check(0))
        t.recordStep()
        assertEquals(Breach.STEPS, t.check(0))
    }

    @Test
    fun `duration budget trips by injected clock`() {
        val t = BudgetTracker(Budget(maxSteps = null, maxDurationMs = 1_000, maxTokens = null), 5_000)
        assertNull(t.check(5_999))
        assertEquals(Breach.DURATION, t.check(6_000))
        assertEquals(0L, t.remainingMs(9_999))
        assertEquals(1_000L, t.remainingMs(5_000))
    }

    @Test
    fun `token budget sums input and output`() {
        val t = BudgetTracker(Budget(maxSteps = null, maxDurationMs = null, maxTokens = 100), 0)
        t.recordTokens(40, 30)
        assertNull(t.check(0))
        t.recordTokens(20, 10)
        assertEquals(Breach.TOKENS, t.check(0))
        assertEquals(100L, t.tokensUsed())
    }

    @Test
    fun `null limits mean unlimited`() {
        val t = BudgetTracker(Budget(null, null, null), 0)
        repeat(10_000) { t.recordStep() }
        t.recordTokens(Long.MAX_VALUE / 4, 0)
        assertNull(t.check(Long.MAX_VALUE / 2))
        assertNull(t.remainingMs(0))
    }
}
