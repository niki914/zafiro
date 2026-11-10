package com.tricreta.zafiro.voice

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceLogicTest {
    @Test
    fun `merge puts one space between typed and spoken text`() {
        assertEquals("check my email", TranscriptMerger.merge("check my", "email"))
        assertEquals("check my email", TranscriptMerger.merge("check my  ", "  email  "))
    }

    @Test
    fun `merge into empty input has no leading space`() {
        assertEquals("hello", TranscriptMerger.merge("", " hello"))
        assertEquals("hello", TranscriptMerger.merge("   ", "hello"))
    }

    @Test
    fun `empty speech leaves the text untouched`() {
        assertEquals("keep me", TranscriptMerger.merge("keep me", "   "))
    }

    @Test
    fun `no match and timeout are friendly retries`() {
        assertEquals(SpeechProblem.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_NO_MATCH).first)
        assertEquals(SpeechProblem.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).first)
    }

    @Test
    fun `permission network and busy map to actionable problems`() {
        assertEquals(SpeechProblem.PERMISSION, SpeechErrors.map(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).first)
        assertEquals(SpeechProblem.NETWORK, SpeechErrors.map(SpeechRecognizer.ERROR_NETWORK).first)
        assertEquals(SpeechProblem.NETWORK, SpeechErrors.map(SpeechRecognizer.ERROR_SERVER).first)
        assertEquals(SpeechProblem.BUSY, SpeechErrors.map(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).first)
    }

    @Test
    fun `unknown codes are reported with the code`() {
        val (problem, message) = SpeechErrors.map(9999)
        assertEquals(SpeechProblem.OTHER, problem)
        assertEquals("Voice input failed (code 9999).", message)
    }
}
