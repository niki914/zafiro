package com.tricreta.zafiro.autonomy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryTest {
    private fun done(text: String = "All good.\nSTATUS: DONE") = TurnReport(TurnEnd.COMPLETED, lastText = text)
    private fun failed(code: String) = TurnReport(TurnEnd.FAILED, failureCode = code, lastText = "partial")

    @Test
    fun `status line is parsed and stripped from the summary`() {
        val s = StatusProtocol.parse("Posted the update.\nSTATUS: DONE")
        assertEquals(StatusKind.DONE, s.kind)
        assertEquals("Posted the update.", s.cleanedText)
    }

    @Test
    fun `needs human carries the reason and tolerates markdown and dashes`() {
        val s = StatusProtocol.parse("Stuck.\n`STATUS: NEEDS_HUMAN - CAPTCHA on login`")
        assertEquals(StatusKind.NEEDS_HUMAN, s.kind)
        assertEquals("CAPTCHA on login", s.detail)
    }

    @Test
    fun `last status line wins and a missing line is MISSING`() {
        assertEquals(StatusKind.FAILED, StatusProtocol.parse("STATUS: DONE\nactually no\nSTATUS: FAILED - broke").kind)
        assertEquals(StatusKind.MISSING, StatusProtocol.parse("just text").kind)
        assertEquals(StatusKind.MISSING, StatusProtocol.parse(null).kind)
    }

    @Test
    fun `plain success without criteria finishes`() {
        val d = RecoveryPlanner(RecoveryConfig()).onTurnEnded(done())
        val f = assertIs<Decision.Finish>(d)
        assertEquals(RunState.SUCCEEDED, f.state)
        assertEquals("All good.", f.summary)
    }

    @Test
    fun `missing status line is still success so chatty models keep working`() {
        val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig()).onTurnEnded(done("Done it.")))
        assertEquals(RunState.SUCCEEDED, f.state)
    }

    @Test
    fun `agent asking for a human ends as NEEDS_HUMAN not failure`() {
        val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig()).onTurnEnded(done("x\nSTATUS: NEEDS_HUMAN - 2FA code")))
        assertEquals(RunState.NEEDS_HUMAN, f.state)
        assertTrue(f.error!!.contains("2FA code"))
    }

    @Test
    fun `agent reporting failure is failure`() {
        val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig()).onTurnEnded(done("x\nSTATUS: FAILED - site down")))
        assertEquals(RunState.FAILED, f.state)
    }

    @Test
    fun `transient failure continues with exponential backoff then gives up`() {
        val p = RecoveryPlanner(RecoveryConfig(recoveries = 2, baseBackoffMs = 1_000, maxBackoffMs = 60_000))
        val first = assertIs<Decision.Continue>(p.onTurnEnded(failed("Transport")))
        assertEquals(1_000, first.delayMs)
        val second = assertIs<Decision.Continue>(p.onTurnEnded(failed("Overloaded")))
        assertEquals(2_000, second.delayMs)
        val third = assertIs<Decision.Finish>(p.onTurnEnded(failed("RateLimit")))
        assertEquals(RunState.FAILED, third.state)
        assertTrue(third.error!!.contains("gave up"))
    }

    @Test
    fun `backoff is capped`() {
        val p = RecoveryPlanner(RecoveryConfig(baseBackoffMs = 10_000, maxBackoffMs = 25_000))
        assertEquals(10_000, p.backoffMs(1))
        assertEquals(20_000, p.backoffMs(2))
        assertEquals(25_000, p.backoffMs(3))
        assertEquals(25_000, p.backoffMs(9))
    }

    @Test
    fun `zero recoveries never retries`() {
        val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig(recoveries = 0)).onTurnEnded(failed("Transport")))
        assertEquals(RunState.FAILED, f.state)
    }

    @Test
    fun `auth quota and config problems need a human and are never retried`() {
        for (code in listOf("Auth", "Quota", "ConfigRequired")) {
            val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig(recoveries = 5)).onTurnEnded(failed(code)))
            assertEquals(RunState.NEEDS_HUMAN, f.state, code)
        }
    }

    @Test
    fun `unknown and tool failures are fatal and are not looped`() {
        for (code in listOf("ToolExecutionFailed", "HookFailed", null, "Whatever")) {
            val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig(recoveries = 5)).onTurnEnded(failed(code.orEmpty())))
            assertEquals(RunState.FAILED, f.state, code)
        }
    }

    @Test
    fun `budget breach wins over everything`() {
        val f = assertIs<Decision.Finish>(
            RecoveryPlanner(RecoveryConfig()).onTurnEnded(TurnReport(TurnEnd.FAILED, "Transport", "x", Breach.TOKENS)),
        )
        assertEquals(RunState.LIMIT_REACHED, f.state)
        assertTrue(f.error!!.contains("token"))
    }

    @Test
    fun `stopped turn is cancelled`() {
        val f = assertIs<Decision.Finish>(RecoveryPlanner(RecoveryConfig()).onTurnEnded(TurnReport(TurnEnd.STOPPED)))
        assertEquals(RunState.CANCELLED, f.state)
    }

    @Test
    fun `success criteria trigger a verification turn and VERIFIED succeeds`() {
        val p = RecoveryPlanner(RecoveryConfig(successCriteria = "The file report.txt exists"))
        val v = assertIs<Decision.Verify>(p.onTurnEnded(done()))
        assertTrue(v.prompt.contains("report.txt"))
        val f = assertIs<Decision.Finish>(p.onTurnEnded(TurnReport(TurnEnd.COMPLETED, lastText = "VERIFIED")))
        assertEquals(RunState.SUCCEEDED, f.state)
    }

    @Test
    fun `INCOMPLETE continues with the missing part then verifies again`() {
        val p = RecoveryPlanner(RecoveryConfig(recoveries = 2, successCriteria = "posted"))
        assertIs<Decision.Verify>(p.onTurnEnded(done()))
        val c = assertIs<Decision.Continue>(p.onTurnEnded(TurnReport(TurnEnd.COMPLETED, lastText = "INCOMPLETE - nothing was posted")))
        assertTrue(c.prompt.contains("nothing was posted"))
        assertEquals(0, c.delayMs)
        assertIs<Decision.Verify>(p.onTurnEnded(done()))
        val f = assertIs<Decision.Finish>(p.onTurnEnded(TurnReport(TurnEnd.COMPLETED, lastText = "VERIFIED")))
        assertEquals(RunState.SUCCEEDED, f.state)
    }

    @Test
    fun `unmet criteria after the last recovery fail honestly`() {
        val p = RecoveryPlanner(RecoveryConfig(recoveries = 0, successCriteria = "posted"))
        assertIs<Decision.Verify>(p.onTurnEnded(done()))
        val f = assertIs<Decision.Finish>(p.onTurnEnded(TurnReport(TurnEnd.COMPLETED, lastText = "INCOMPLETE - nothing posted")))
        assertEquals(RunState.FAILED, f.state)
        assertTrue(f.error!!.contains("nothing posted"))
    }

    @Test
    fun `an unclear verification reply is not treated as success`() {
        val p = RecoveryPlanner(RecoveryConfig(recoveries = 0, successCriteria = "x"))
        assertIs<Decision.Verify>(p.onTurnEnded(done()))
        val f = assertIs<Decision.Finish>(p.onTurnEnded(TurnReport(TurnEnd.COMPLETED, lastText = "I think it went fine")))
        assertEquals(RunState.FAILED, f.state)
    }

    @Test
    fun `failure during a verification turn is handled as a failure`() {
        val p = RecoveryPlanner(RecoveryConfig(recoveries = 1, successCriteria = "x", baseBackoffMs = 5))
        assertIs<Decision.Verify>(p.onTurnEnded(done()))
        assertIs<Decision.Continue>(p.onTurnEnded(failed("Transport")))
        assertNull((p.onTurnEnded(done()) as? Decision.Finish)?.error)
    }
}
