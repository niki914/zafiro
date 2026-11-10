package com.tricreta.zafiro.voice.dictation

import com.tricreta.zafiro.voice.dictation.DictationSession.Fault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeTimer : DictationTimer {
    private class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)

    private val tasks = mutableListOf<Task>()
    var now = 0L
        private set

    override fun postDelayed(delayMs: Long, block: () -> Unit): DictationTimer.Handle {
        val t = Task(now + delayMs, block)
        tasks += t
        return object : DictationTimer.Handle {
            override fun cancel() {
                t.cancelled = true
            }
        }
    }

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            now = next.at
            tasks.remove(next)
            next.block()
        }
        now = target
    }
}

private class FakeEngine : SpeechEngine {
    val sessions = mutableListOf<SpeechEngine.Listener>()
    var stopCalls = 0
    var cancelCalls = 0
    var released = false
    var failNextListen: Throwable? = null
    val current get() = sessions.last()

    override fun listen(languageTag: String?, listener: SpeechEngine.Listener) {
        failNextListen?.let { failNextListen = null; throw it }
        sessions += listener
    }

    override fun stop() { stopCalls++ }
    override fun cancel() { cancelCalls++ }
    override fun release() { released = true }
}

class ContinuousDictationTest {
    private val timer = FakeTimer()
    private val engine = FakeEngine()
    private val transcripts = mutableListOf<String>()
    private val listening = mutableListOf<Boolean>()
    private val ends = mutableListOf<EndReason>()
    private val cfg = DictationSession.Config()

    private fun newDictation() = ContinuousDictation(
        engine = engine, timer = timer,
        onTranscript = { transcripts += it }, onListening = { listening += it }, onEnd = { ends += it },
    )

    @Test
    fun `speech continues across many pauses until the user stops`() {
        val d = newDictation()
        d.start()
        assertEquals(1, engine.sessions.size)
        engine.current.onPartial("please")
        engine.current.onResult("please open")
        // the recogniser ended after a pause: a NEW session must start by itself
        timer.advance(cfg.restartDelayMs)
        assertEquals(2, engine.sessions.size)
        engine.current.onResult("the calendar")
        timer.advance(cfg.restartDelayMs)
        assertEquals(3, engine.sessions.size)
        engine.current.onPartial("and add")
        assertEquals("please open the calendar and add", transcripts.last())
        assertTrue(d.isActive)
        assertTrue(ends.isEmpty())

        d.stop()
        assertEquals(1, engine.stopCalls)
        engine.current.onResult("and add a meeting")
        assertEquals(listOf<EndReason>(EndReason.UserStopped), ends)
        assertEquals("please open the calendar and add a meeting", transcripts.last())
        assertEquals(listOf(true, false), listening)
        assertFalse(d.isActive)
    }

    @Test
    fun `every recogniser session reuses the same engine instance`() {
        val d = newDictation()
        d.start()
        repeat(5) { engine.current.onResult("w"); timer.advance(cfg.restartDelayMs) }
        assertEquals(6, engine.sessions.size)
        assertFalse(engine.released) // never destroyed between pauses
    }

    @Test
    fun `late callbacks from an ended session are ignored`() {
        val d = newDictation()
        d.start()
        val first = engine.current
        first.onResult("one")
        timer.advance(cfg.restartDelayMs)
        first.onResult("GHOST")           // stale: belongs to the first session
        first.onPartial("GHOST")
        first.onError(Fault.FATAL, 9, "x")
        engine.current.onResult("two")
        assertEquals("one two", transcripts.last())
        assertTrue(d.isActive)
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `tapping stop between two sessions ends at once and keeps the text`() {
        val d = newDictation()
        d.start()
        engine.current.onResult("kept")
        d.stop() // restart delay not elapsed yet
        assertEquals(listOf<EndReason>(EndReason.UserStopped), ends)
        assertEquals("kept", transcripts.last())
        timer.advance(10_000)
        assertEquals(1, engine.sessions.size) // the pending restart was cancelled
    }

    @Test
    fun `stop that the recogniser never answers still ends after the timeout`() {
        val d = newDictation()
        d.start()
        engine.current.onPartial("half a sentence")
        d.stop()
        assertTrue(ends.isEmpty())
        timer.advance(2_600)
        assertEquals(listOf<EndReason>(EndReason.UserStopped), ends)
        assertEquals("half a sentence", transcripts.last())
    }

    @Test
    fun `long silence ends quietly with the text kept`() {
        val d = newDictation()
        d.start()
        engine.current.onResult("hello")
        repeat(cfg.maxConsecutiveSilent) {
            timer.advance(cfg.restartDelayMs)
            engine.current.onError(Fault.NO_SPEECH, 7, "no match")
        }
        assertEquals(listOf<EndReason>(EndReason.SilenceTimeout), ends)
        assertEquals("hello", transcripts.last())
    }

    @Test
    fun `a fatal error ends with a readable message and keeps the text`() {
        val d = newDictation()
        d.start()
        engine.current.onResult("saved")
        timer.advance(cfg.restartDelayMs)
        engine.current.onError(Fault.FATAL, 9, "Microphone permission is needed for voice input.")
        val end = assertIs<EndReason.Failed>(ends.single())
        assertEquals(9, end.code)
        assertEquals("Microphone permission is needed for voice input.", end.message)
        assertEquals("saved", transcripts.last())
    }

    @Test
    fun `transient faults are retried and a good result resets the budget`() {
        val d = newDictation()
        d.start()
        repeat(cfg.maxTransientRetries) {
            engine.current.onError(Fault.TRANSIENT, 2, "net")
            timer.advance(cfg.transientDelayMs)
        }
        engine.current.onResult("recovered")
        timer.advance(cfg.restartDelayMs)
        assertTrue(d.isActive)
        assertEquals("recovered", transcripts.last())
    }

    @Test
    fun `an engine that throws on listen is treated as a transient fault, not a crash`() {
        val d = newDictation()
        engine.failNextListen = IllegalStateException("service gone")
        d.start()
        timer.advance(cfg.transientDelayMs)
        assertEquals(1, engine.sessions.size) // the retry succeeded
        assertTrue(d.isActive)
    }

    @Test
    fun `release keeps what was heard, reports Released, and frees the engine`() {
        val d = newDictation()
        d.start()
        engine.current.onResult("a")
        timer.advance(cfg.restartDelayMs)
        engine.current.onPartial("b")
        d.release()
        assertEquals("a b", transcripts.last())
        assertEquals(listOf<EndReason>(EndReason.Released), ends)
        assertTrue(engine.released)
        d.start() // cannot be restarted after release
        assertEquals(2, engine.sessions.size)
    }

    @Test
    fun `starting twice does not create a second loop`() {
        val d = newDictation()
        d.start(); d.start()
        assertEquals(1, engine.sessions.size)
    }

    @Test
    fun `a 40 utterance monologue loses nothing`() {
        val d = newDictation()
        d.start()
        val words = (1..40).map { "w$it" }
        words.forEach { engine.current.onResult(it); timer.advance(cfg.restartDelayMs) }
        assertEquals(words.joinToString(" "), transcripts.last())
        assertTrue(d.isActive)
    }
}
