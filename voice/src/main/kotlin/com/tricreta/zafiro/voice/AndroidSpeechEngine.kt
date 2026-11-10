package com.tricreta.zafiro.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.niki914.logging.Logger
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault
import com.tricreta.zafiro.voice.dictation.SpeechEngine

/**
 * [SpeechEngine] over the system recogniser. ONE SpeechRecognizer instance is reused for every session (cancel + startListening
 * again), because destroying and recreating it on every pause is what makes some recogniser services answer BUSY or CLIENT
 * errors. It is recreated only after an error that suggests the service connection is unhealthy.
 * Main thread only (SpeechRecognizer requirement).
 */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {

    private var recognizer: SpeechRecognizer? = null
    private var recreateBeforeNextListen = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(languageTag: String?, listener: SpeechEngine.Listener) {
        if (!isAvailable()) {
            listener.onError(
                Fault.FATAL, -2,
                "No speech recognition service is installed on this phone (install or enable Google's speech services).",
            )
            return
        }
        if (recreateBeforeNextListen) {
            destroy()
            recreateBeforeNextListen = false
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                bestText(partialResults).takeIf { it.isNotEmpty() }?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                listener.onResult(bestText(results))
            }

            override fun onError(error: Int) {
                val mapped = SpeechErrors.map(error)
                Logger.w(LOG_TAG, "recognition error code=$error fault=${mapped.fault}")
                if (mapped.fault != Fault.NO_SPEECH) recreateBeforeNextListen = true
                listener.onError(mapped.fault, error, mapped.message)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints only: many recognisers ignore them, which is why the restart loop exists.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    override fun release() = destroy()

    private fun destroy() {
        recognizer?.let {
            it.setRecognitionListener(null)
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun bestText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private companion object {
        const val LOG_TAG = "AndroidSpeechEngine"
        const val SILENCE_HINT_MS = 3_000L
    }
}
