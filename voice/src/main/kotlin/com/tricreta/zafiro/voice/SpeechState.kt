package com.tricreta.zafiro.voice

import android.speech.SpeechRecognizer
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault

enum class SpeechProblem { NO_SPEECH, NO_RECOGNIZER, PERMISSION, NETWORK, BUSY, AUDIO, OTHER }

sealed interface SpeechState {
    data object Idle : SpeechState

    /** [transcript] is everything heard so far in this dictation (all segments plus the live partial). */
    data class Listening(val transcript: String) : SpeechState

    data class Failed(val problem: SpeechProblem, val message: String) : SpeechState
}

object SpeechErrors {
    data class Mapped(val problem: SpeechProblem, val message: String, val fault: Fault)

    /** Maps SpeechRecognizer.ERROR_* to a user message and to the retry policy class used by the dictation loop. */
    fun map(code: Int): Mapped = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            Mapped(SpeechProblem.NO_SPEECH, "I didn't catch that. Tap the mic and try again.", Fault.NO_SPEECH)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            Mapped(SpeechProblem.BUSY, "The speech service is busy. Try again in a moment.", Fault.BUSY)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            Mapped(SpeechProblem.NETWORK, "Speech recognition needs a working internet connection.", Fault.TRANSIENT)
        // Raised when a session is torn down underneath us; retrying a fresh session normally works.
        SpeechRecognizer.ERROR_CLIENT ->
            Mapped(SpeechProblem.OTHER, "Voice input was interrupted. Try again.", Fault.TRANSIENT)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Mapped(SpeechProblem.PERMISSION, "Microphone permission is needed for voice input.", Fault.FATAL)
        SpeechRecognizer.ERROR_AUDIO ->
            Mapped(SpeechProblem.AUDIO, "The microphone could not be used. Is another app recording?", Fault.FATAL)
        else -> Mapped(SpeechProblem.OTHER, "Voice input failed (code $code).", Fault.FATAL)
    }
}

object TranscriptMerger {
    /** Appends spoken text to what is already typed, with one space between, never a leading space. */
    fun merge(existing: String, spoken: String): String {
        val s = spoken.trim()
        if (s.isEmpty()) return existing
        if (existing.isBlank()) return s
        return existing.trimEnd() + " " + s
    }
}
