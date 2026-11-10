package com.tricreta.zafiro.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.niki914.logging.Logger
import com.tricreta.zafiro.voice.dictation.DictationSession
import com.tricreta.zafiro.voice.dictation.DictationSession.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous dictation on top of the system speech recogniser.
 *
 * The recogniser itself is single-utterance: it returns one result after the first pause and ends. This class therefore runs a
 * LOOP of recognition sessions and feeds every result into a [DictationSession], which decides (and unit-tests) when to restart,
 * when silence means "finished", and which errors are worth retrying. The loop ends only when the user stops, a long silence
 * passes, or the recogniser reports an error that retrying cannot fix. Text accumulates; later speech never overwrites earlier.
 *
 * Main-thread only (SpeechRecognizer requirement). Every recogniser callback is tagged with its session number so a late
 * callback from an already-destroyed recogniser cannot corrupt the next one.
 *
 * Known platform behaviour: Google's recogniser may play a short start/stop sound on each internal restart; Android gives apps
 * no supported way to silence it.
 */
class VoiceInput(private val context: Context) {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var session: DictationSession? = null
    private var recognizer: SpeechRecognizer? = null
    private var onTranscript: ((String) -> Unit)? = null
    private var languageTag: String? = null
    private var segmentId = 0
    private var restartPending = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    val isActive: Boolean get() = session?.isFinished == false

    /**
     * @param languageTag BCP-47 such as "en-KE"; null = device default.
     * @param onTranscript called on every change with the FULL transcript of this dictation (not a delta).
     */
    fun start(languageTag: String? = null, onTranscript: (String) -> Unit) {
        if (isActive) return
        if (!isAvailable()) {
            _state.value = SpeechState.Failed(
                SpeechProblem.NO_RECOGNIZER,
                "No speech recognition service is installed on this phone (install or enable Google's speech services).",
            )
            return
        }
        this.languageTag = languageTag
        this.onTranscript = onTranscript
        lastFatal = null
        session = DictationSession()
        _state.value = SpeechState.Listening("")
        beginSegment()
    }

    /** The user is done. Whatever the recogniser has is delivered, then dictation ends. */
    fun stop() {
        val s = session ?: return
        if (s.isFinished) return
        s.requestStop()
        if (restartPending || recognizer == null) {
            // Between two recogniser sessions there is nothing to wait for.
            handler.removeCallbacksAndMessages(null)
            act(s.abort())
            return
        }
        recognizer?.stopListening()
        // If the recogniser never answers the stop request, do not hang the button forever.
        val id = segmentId
        handler.postDelayed({ if (id == segmentId) session?.let { act(it.abort()) } }, STOP_TIMEOUT_MS)
    }

    /** Tear everything down (screen closed). Keeps nothing running. */
    fun release() {
        session?.let { if (!it.isFinished) act(it.abort()) }
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        session = null
        onTranscript = null
    }

    fun clearError() {
        if (_state.value is SpeechState.Failed) _state.value = SpeechState.Idle
    }

    private fun beginSegment() {
        val s = session ?: return
        if (s.isFinished) return
        restartPending = false
        destroyRecognizer()
        val id = ++segmentId
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listenerFor(id))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints only: some recognisers ignore them, which is exactly why the restart loop exists.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Logger.e(LOG_TAG, "startListening failed", t)
            act(s.onFault(DictationSession.Fault.TRANSIENT))
        }
    }

    private fun act(action: Action) {
        when (action) {
            is Action.Restart -> {
                restartPending = true
                destroyRecognizer()
                val id = segmentId
                handler.postDelayed({ if (id == segmentId && restartPending) beginSegment() }, action.delayMs)
            }
            Action.Finish -> end(null)
            is Action.Fail -> end(action)
            Action.None -> Unit
        }
        publish()
    }

    private fun publish() {
        val s = session ?: return
        if (!s.isFinished) _state.value = SpeechState.Listening(s.transcript)
        onTranscript?.invoke(s.transcript)
    }

    private fun end(failure: Action.Fail?) {
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        _state.value = if (failure == null) {
            SpeechState.Idle
        } else {
            val m = when (failure.fault) {
                DictationSession.Fault.BUSY -> SpeechErrors.map(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                DictationSession.Fault.TRANSIENT -> SpeechErrors.map(SpeechRecognizer.ERROR_NETWORK)
                else -> lastFatal ?: SpeechErrors.map(-1)
            }
            SpeechState.Failed(m.problem, m.message)
        }
    }

    private var lastFatal: SpeechErrors.Mapped? = null

    private fun destroyRecognizer() {
        recognizer?.let {
            it.setRecognitionListener(null)
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun listenerFor(id: Int) = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            if (id != segmentId) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val s = session ?: return
            if (text.isNotEmpty()) {
                s.onPartial(text)
                publish()
            }
        }

        override fun onResults(results: Bundle?) {
            if (id != segmentId) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val s = session ?: return
            act(s.onFinalResult(text))
        }

        override fun onError(error: Int) {
            if (id != segmentId) return
            val s = session ?: return
            val mapped = SpeechErrors.map(error)
            Logger.w(LOG_TAG, "recognition error code=$error fault=${mapped.fault}")
            if (mapped.fault == DictationSession.Fault.FATAL) lastFatal = mapped
            act(s.onFault(mapped.fault))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val LOG_TAG = "VoiceInput"
        const val SILENCE_HINT_MS = 3_000L
        const val STOP_TIMEOUT_MS = 2_500L
    }
}
