package com.tricreta.zafiro.voice.dictation

import com.tricreta.zafiro.voice.dictation.DictationSession.Action
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault

/** One reusable speech recogniser. The Android adapter wraps SpeechRecognizer; tests use a fake. */
interface SpeechEngine {
    interface Listener {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(fault: Fault, code: Int, message: String)
    }

    /** Start ONE recognition session. Calling it again later reuses the same engine. */
    fun listen(languageTag: String?, listener: Listener)

    /** Ask the current session to deliver what it has heard (user tapped stop). */
    fun stop()

    /** Abort the current session without delivering anything. The engine stays usable. */
    fun cancel()

    /** Free everything. */
    fun release()
}

interface DictationTimer {
    interface Handle {
        fun cancel()
    }

    fun postDelayed(delayMs: Long, block: () -> Unit): Handle
}

sealed interface EndReason {
    /** The user tapped stop. Never worth telling them about. */
    data object UserStopped : EndReason

    /** Long silence: ended on its own, text kept. */
    data object SilenceTimeout : EndReason

    /** The recogniser failed in a way retrying cannot fix. [message] is user-readable and says what to do. */
    data class Failed(val fault: Fault, val code: Int?, val message: String) : EndReason

    /** The screen went away. */
    data object Released : EndReason
}

/**
 * Orchestrates continuous dictation: a loop of short recogniser sessions whose results are accumulated by [DictationSession].
 * Pure Kotlin (engine and timer are injected) so the restart loop, stale-callback protection and stop handling are unit-tested.
 *
 * Lifetime rule: whoever owns this object decides when it dies. In the app it is owned by the chat composer ITSELF, not by a
 * button, because the composer swaps between a compact and an expanded layout while you dictate; an owner that lives inside
 * one layout would be destroyed mid-sentence.
 */
class ContinuousDictation(
    private val engine: SpeechEngine,
    private val timer: DictationTimer,
    private val onTranscript: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onEnd: (EndReason) -> Unit,
    private val languageTag: String? = null,
    private val config: DictationSession.Config = DictationSession.Config(),
    private val stopTimeoutMs: Long = 2_500L,
) {
    private var session: DictationSession? = null
    private var generation = 0
    private var restartTimer: DictationTimer.Handle? = null
    private var stopTimer: DictationTimer.Handle? = null
    private var engineBusy = false
    private var userStopped = false
    private var released = false
    private var lastError: SpeechEngine.Listener? = null
    private var lastFaultCode: Int? = null
    private var lastFaultMessage: String? = null
    private var lastEmitted: String? = null

    val isActive: Boolean get() = session?.isFinished == false

    fun start() {
        if (isActive || released) return
        session = DictationSession(config)
        userStopped = false
        lastFaultCode = null
        lastFaultMessage = null
        lastEmitted = null
        onListening(true)
        beginSegment()
    }

    /** The user is done: deliver what the recogniser has, then end. */
    fun stop() {
        val s = session ?: return
        if (s.isFinished) return
        userStopped = true
        s.requestStop()
        if (!engineBusy) {
            // Between two recogniser sessions there is nothing to wait for.
            restartTimer?.cancel()
            act(s.abort())
            return
        }
        engine.stop()
        // If the recogniser never answers the stop request, do not leave the button hanging.
        val gen = generation
        stopTimer = timer.postDelayed(stopTimeoutMs) { if (gen == generation) session?.let { act(it.abort()) } }
    }

    /** Tear down for good (the owner is going away). Keeps whatever was heard. */
    fun release() {
        if (released) return
        released = true
        session?.let { if (!it.isFinished) act(it.abort()) }
        restartTimer?.cancel()
        stopTimer?.cancel()
        generation++
        engine.release()
    }

    private fun beginSegment() {
        val s = session ?: return
        if (s.isFinished) return
        restartTimer = null
        val gen = ++generation
        engineBusy = true
        val listener = object : SpeechEngine.Listener {
            override fun onPartial(text: String) {
                if (gen != generation) return // callback from a session that already ended
                s.onPartial(text)
                emit()
            }

            override fun onResult(text: String) {
                if (gen != generation) return
                engineBusy = false
                act(s.onFinalResult(text))
            }

            override fun onError(fault: Fault, code: Int, message: String) {
                if (gen != generation) return
                engineBusy = false
                lastFaultCode = code
                lastFaultMessage = message
                act(s.onFault(fault))
            }
        }
        try {
            engine.listen(languageTag, listener)
        } catch (t: Throwable) {
            engineBusy = false
            lastFaultMessage = "Voice input could not start: ${t.message ?: t::class.java.simpleName}"
            act(s.onFault(Fault.TRANSIENT))
        }
    }

    private fun act(action: Action) {
        when (action) {
            is Action.Restart -> {
                engine.cancel()
                val gen = generation
                restartTimer = timer.postDelayed(action.delayMs) { if (gen == generation) beginSegment() }
            }
            Action.Finish -> finish(
                when {
                    released -> EndReason.Released
                    userStopped -> EndReason.UserStopped
                    else -> EndReason.SilenceTimeout
                },
            )
            is Action.Fail -> finish(
                EndReason.Failed(
                    fault = action.fault,
                    code = lastFaultCode,
                    message = lastFaultMessage ?: "Voice input stopped (${action.fault.name.lowercase()}).",
                ),
            )
            Action.None -> Unit
        }
        emit()
    }

    private fun finish(reason: EndReason) {
        generation++ // anything still in flight is now stale
        engineBusy = false
        restartTimer?.cancel()
        stopTimer?.cancel()
        engine.cancel()
        emit()
        onListening(false)
        onEnd(reason)
    }

    private fun emit() {
        val text = session?.transcript ?: return
        if (text != lastEmitted) {
            lastEmitted = text
            onTranscript(text)
        }
    }
}
