package com.tricreta.zafiro.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.niki914.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot dictation using the system speech recogniser (Google's on most phones, including Tecno/Samsung).
 * Main-thread only, as SpeechRecognizer requires. Partial results stream into [state]; the final text goes to [onFinal].
 *
 * Needs RECORD_AUDIO granted (the caller requests it). If no recogniser service is installed, [isAvailable] is false and
 * the UI should say so instead of failing silently. A cloud transcription fallback is planned (ROADMAP: Voice).
 */
class VoiceInput(private val context: Context) {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** @param languageTag BCP-47 such as "en-KE"; null = device default. */
    fun start(languageTag: String? = null, onFinal: (String) -> Unit) {
        if (!isAvailable()) {
            _state.value = SpeechState.Failed(
                SpeechProblem.NO_RECOGNIZER,
                "No speech recognition service is installed on this phone (install or enable Google's speech services).",
            )
            return
        }
        cancel()
        this.onFinal = onFinal
        val r = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        _state.value = SpeechState.Listening("")
        r.startListening(intent)
    }

    /** User is done talking: ask the recogniser to deliver what it has. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Abort without delivering anything. */
    fun cancel() {
        recognizer?.let {
            it.setRecognitionListener(null)
            it.cancel()
            it.destroy()
        }
        recognizer = null
        onFinal = null
        if (_state.value is SpeechState.Listening) _state.value = SpeechState.Idle
    }

    fun release() = cancel()

    fun clearError() {
        if (_state.value is SpeechState.Failed) _state.value = SpeechState.Idle
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) _state.value = SpeechState.Listening(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val callback = onFinal
            finishSession()
            if (text.isNotBlank()) callback?.invoke(text)
        }

        override fun onError(error: Int) {
            Logger.w(LOG_TAG, "recognition error code=$error")
            finishSession()
            val (problem, message) = SpeechErrors.map(error)
            _state.value = SpeechState.Failed(problem, message)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishSession() {
        recognizer?.destroy()
        recognizer = null
        onFinal = null
        _state.value = SpeechState.Idle
    }

    private companion object {
        const val LOG_TAG = "VoiceInput"
    }
}
