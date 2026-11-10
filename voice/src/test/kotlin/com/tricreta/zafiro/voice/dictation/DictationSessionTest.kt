package com.tricreta.zafiro.voice.dictation

import com.tricreta.zafiro.voice.dictation.DictationSession.Action
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DictationSessionTest {
    private val cfg = DictationSession.Config()

    @Test
    fun `the reported bug - one word then a pause must restart, not stop`() {
        val s = DictationSession()
        s.onPartial("hello")
        val a = s.onFinalResult("hello")
        assertEquals(Action.Restart(cfg.restartDelayMs), a)
        assertEquals("hello", s.transcript)
        assertTrue(!s.isFinished)
    }

    @Test
    fun `speech across many pauses accumulates in order and never overwrites`() {
        val s = DictationSession()
        s.onFinalResult("please open")
        s.onPartial("the calendar")
        assertEquals("please open the calendar", s.transcript)
        s.onFinalResult("the calendar")
        s.onPartial("and add")
        s.onFinalResult("and add a meeting")
        assertEquals("please open the calendar and add a meeting", s.transcript)
    }

    @Test
    fun `a partial only replaces the previous partial, not committed text`() {
        val s = DictationSession()
        s.onFinalResult("one")
        s.onPartial("tw")
        s.onPartial("two")
        assertEquals("one two", s.transcript)
    }

    @Test
    fun `user stop delivers the last result and then finishes without restarting`() {
        val s = DictationSession()
        s.onFinalResult("first")
        s.requestStop()
        assertEquals(Action.Finish, s.onFinalResult("second"))
        assertEquals("first second", s.transcript)
        assertTrue(s.isFinished)
    }

    @Test
    fun `user stop with a no-match error still finishes and keeps the text`() {
        val s = DictationSession()
        s.onFinalResult("keep this")
        s.requestStop()
        assertEquals(Action.Finish, s.onFault(Fault.NO_SPEECH))
        assertEquals("keep this", s.transcript)
    }

    @Test
    fun `stopping while only a partial exists commits the partial`() {
        val s = DictationSession()
        s.onPartial("half a sentence")
        s.requestStop()
        assertEquals(Action.Finish, s.onFault(Fault.NO_SPEECH))
        assertEquals("half a sentence", s.transcript)
    }

    @Test
    fun `silence is normal - restart a few times then end quietly keeping the text`() {
        val s = DictationSession()
        s.onFinalResult("hello")
        repeat(cfg.maxConsecutiveSilent - 1) { assertIs<Action.Restart>(s.onFault(Fault.NO_SPEECH)) }
        assertEquals(Action.Finish, s.onFault(Fault.NO_SPEECH))
        assertEquals("hello", s.transcript)
    }

    @Test
    fun `speaking resets the silence counter`() {
        val s = DictationSession()
        repeat(cfg.maxConsecutiveSilent - 1) { s.onFault(Fault.NO_SPEECH) }
        s.onFinalResult("still here")
        repeat(cfg.maxConsecutiveSilent - 1) { assertIs<Action.Restart>(s.onFault(Fault.NO_SPEECH)) }
        assertTrue(!s.isFinished)
    }

    @Test
    fun `blank results count as silence and add no stray spaces`() {
        val s = DictationSession()
        s.onFinalResult("a")
        s.onFinalResult("   ")
        s.onFinalResult("b")
        assertEquals("a b", s.transcript)
    }

    @Test
    fun `busy recogniser is retried with a longer delay and then reported`() {
        val s = DictationSession()
        repeat(cfg.maxBusyRetries) { assertEquals(Action.Restart(cfg.busyDelayMs), s.onFault(Fault.BUSY)) }
        assertEquals(Action.Fail(Fault.BUSY), s.onFault(Fault.BUSY))
    }

    @Test
    fun `transient network faults get limited retries and a successful result resets them`() {
        val s = DictationSession()
        repeat(cfg.maxTransientRetries) { assertIs<Action.Restart>(s.onFault(Fault.TRANSIENT)) }
        s.onFinalResult("recovered")
        repeat(cfg.maxTransientRetries) { assertIs<Action.Restart>(s.onFault(Fault.TRANSIENT)) }
        assertEquals(Action.Fail(Fault.TRANSIENT), s.onFault(Fault.TRANSIENT))
        assertEquals("recovered", s.transcript)
    }

    @Test
    fun `fatal faults fail immediately but keep what was heard`() {
        val s = DictationSession()
        s.onFinalResult("saved")
        s.onPartial("in flight")
        assertEquals(Action.Fail(Fault.FATAL), s.onFault(Fault.FATAL))
        assertEquals("saved in flight", s.transcript)
    }

    @Test
    fun `abort commits the partial and finishes`() {
        val s = DictationSession()
        s.onFinalResult("a")
        s.onPartial("b")
        assertEquals(Action.Finish, s.abort())
        assertEquals("a b", s.transcript)
        assertEquals(Action.None, s.onFinalResult("late"))
        assertEquals("a b", s.transcript)
    }

    @Test
    fun `a long dictation of 50 utterances keeps every one`() {
        val s = DictationSession()
        val words = (1..50).map { "w$it" }
        words.forEach { s.onPartial(it); s.onFinalResult(it) }
        assertEquals(words.joinToString(" "), s.transcript)
    }
}
