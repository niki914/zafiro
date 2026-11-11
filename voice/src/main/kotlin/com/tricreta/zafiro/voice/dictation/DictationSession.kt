package com.tricreta.zafiro.voice.dictation

/**
 * Pure decision logic for continuous dictation. No Android types, so it is unit-tested on the JVM.
 *
 * Why this exists: Android's SpeechRecognizer is single-utterance by design. After the first pause it delivers ONE result and
 * ends. Treating that as "done" is what made dictation stop after a single word. Continuous dictation is therefore a LOOP of
 * short recognition sessions whose results are accumulated here; the loop ends only when the user stops, the user stays silent
 * for a long time, or the recogniser hits an error that retrying cannot fix.
 */
class DictationSession(private val config: Config = Config()) {

    data class Config(
        /** Consecutive empty sessions (no speech heard) before dictation ends quietly. */
        val maxConsecutiveSilent: Int = 4,
        /** Retries when the recogniser says it is busy (usually because we restarted too fast). */
        val maxBusyRetries: Int = 4,
        /** Retries for transient network/server faults before giving up. */
        val maxTransientRetries: Int = 2,
        val restartDelayMs: Long = 150,
        val busyDelayMs: Long = 600,
        val transientDelayMs: Long = 800,
    )

    /** Platform-independent classification of a recogniser error (the Android layer maps its codes onto these). */
    enum class Fault {
        /** Nothing was recognised or the user was silent. Normal in continuous dictation. */
        NO_SPEECH,

        /** Recogniser still busy with the previous session. Retry shortly. */
        BUSY,

        /** Network/server hiccup. Retry a couple of times. */
        TRANSIENT,

        /** Retrying cannot help (permission, no service, microphone unusable, unsupported language…). */
        FATAL,
    }

    sealed interface Action {
        /** Start another recognition session after [delayMs]. */
        data class Restart(val delayMs: Long) : Action

        /** Dictation is over; keep the accumulated text. */
        data object Finish : Action

        /** Dictation failed; the accumulated text is still available via [transcript]. */
        data class Fail(val fault: Fault) : Action

        /** Nothing to do. */
        data object None : Action
    }

    private var committed = ""
    private var partial = ""
    private var stopRequested = false
    private var finished = false
    private var silentStreak = 0
    private var busyRetries = 0
    private var transientRetries = 0

    /** Everything heard so far: committed segments plus the live partial. */
    val transcript: String get() = join(committed, partial)

    val isFinished: Boolean get() = finished

    /** Live partial result of the current recogniser session. Replaces the previous partial, never the committed text. */
    fun onPartial(text: String) {
        if (finished) return
        partial = text.trim()
    }

    /** A recogniser session delivered its final result. */
    fun onFinalResult(text: String): Action {
        if (finished) return Action.None
        val heard = text.trim()
        partial = ""
        if (heard.isNotEmpty()) {
            committed = join(committed, heard)
            silentStreak = 0
            busyRetries = 0
            transientRetries = 0
        } else {
            silentStreak++
        }
        return next(silentOk = true)
    }

    /** A recogniser session ended with a fault. */
    fun onFault(fault: Fault): Action {
        if (finished) return Action.None
        // Keep whatever was heard but not finalised: losing it would be worse than a duplicate-free guess.
        if (partial.isNotEmpty()) {
            committed = join(committed, partial)
            partial = ""
            silentStreak = 0
        }
        if (stopRequested) return finish()
        return when (fault) {
            Fault.NO_SPEECH -> {
                silentStreak++
                next(silentOk = true)
            }
            Fault.BUSY ->
                if (++busyRetries > config.maxBusyRetries) fail(fault) else Action.Restart(config.busyDelayMs)
            Fault.TRANSIENT ->
                if (++transientRetries > config.maxTransientRetries) fail(fault) else Action.Restart(config.transientDelayMs)
            Fault.FATAL -> fail(fault)
        }
    }

    /**
     * The user tapped stop. The caller asks the recogniser to deliver what it has (stopListening), which then arrives through
     * [onFinalResult] or [onFault] and ends the session; no new session is started.
     */
    fun requestStop() {
        stopRequested = true
    }

    /** Abort immediately, committing any partial text (used when the screen goes away). */
    fun abort(): Action {
        if (finished) return Action.None
        if (partial.isNotEmpty()) {
            committed = join(committed, partial)
            partial = ""
        }
        return finish()
    }

    private fun next(silentOk: Boolean): Action = when {
        stopRequested -> finish()
        silentOk && silentStreak >= config.maxConsecutiveSilent -> finish()
        else -> Action.Restart(config.restartDelayMs)
    }

    private fun finish(): Action {
        finished = true
        return Action.Finish
    }

    private fun fail(fault: Fault): Action {
        finished = true
        return Action.Fail(fault)
    }

    private fun join(a: String, b: String): String = when {
        b.isBlank() -> a
        a.isBlank() -> b.trim()
        else -> a.trimEnd() + " " + b.trim()
    }
}
