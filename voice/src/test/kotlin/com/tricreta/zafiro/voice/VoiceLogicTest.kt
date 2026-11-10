package com.tricreta.zafiro.voice

import android.speech.SpeechRecognizer
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault
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
        assertEquals(SpeechProblem.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_NO_MATCH).problem)
        assertEquals(SpeechProblem.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).problem)
    }

    @Test
    fun `permission network and busy map to actionable problems`() {
        assertEquals(SpeechProblem.PERMISSION, SpeechErrors.map(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).problem)
        assertEquals(SpeechProblem.NETWORK, SpeechErrors.map(SpeechRecognizer.ERROR_NETWORK).problem)
        assertEquals(SpeechProblem.NETWORK, SpeechErrors.map(SpeechRecognizer.ERROR_SERVER).problem)
        assertEquals(SpeechProblem.BUSY, SpeechErrors.map(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).problem)
    }

    @Test
    fun `unknown codes are reported with the code`() {
        val m = SpeechErrors.map(9999)
        assertEquals(SpeechProblem.OTHER, m.problem)
        assertEquals("Voice input failed (code 9999).", m.message)
    }

    @Test
    fun `faults map onto the retry classes the dictation loop uses`() {
        assertEquals(Fault.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_NO_MATCH).fault)
        assertEquals(Fault.NO_SPEECH, SpeechErrors.map(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).fault)
        assertEquals(Fault.BUSY, SpeechErrors.map(SpeechRecognizer.ERROR_RECOGNIZER_BUSY).fault)
        assertEquals(Fault.TRANSIENT, SpeechErrors.map(SpeechRecognizer.ERROR_NETWORK_TIMEOUT).fault)
        assertEquals(Fault.TRANSIENT, SpeechErrors.map(SpeechRecognizer.ERROR_CLIENT).fault)
        assertEquals(Fault.FATAL, SpeechErrors.map(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).fault)
        assertEquals(Fault.FATAL, SpeechErrors.map(SpeechRecognizer.ERROR_AUDIO).fault)
    }
}
