package com.tricreta.zafiro.voice

import android.speech.SpeechRecognizer

enum class SpeechProblem { NO_SPEECH, NO_RECOGNIZER, PERMISSION, NETWORK, BUSY, AUDIO, OTHER }

sealed interface SpeechState {
    data object Idle : SpeechState

    /** [partial] is the live transcript so far (may be empty). */
    data class Listening(val partial: String) : SpeechState

    data class Failed(val problem: SpeechProblem, val message: String) : SpeechState
}

object SpeechErrors {
    /** Maps SpeechRecognizer.ERROR_* to a problem and a message that tells the user what to do. */
    fun map(code: Int): Pair<SpeechProblem, String> = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            SpeechProblem.NO_SPEECH to "I didn't catch that. Tap the mic and try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechProblem.PERMISSION to "Microphone permission is needed for voice input."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            SpeechProblem.NETWORK to "Speech recognition needs an internet connection right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechProblem.BUSY to "The speech service is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO ->
            SpeechProblem.AUDIO to "The microphone could not be used. Is another app recording?"
        else -> SpeechProblem.OTHER to "Voice input failed (code $code)."
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
