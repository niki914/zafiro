package com.tricreta.zafiro.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tricreta.zafiro.voice.dictation.ContinuousDictation
import com.tricreta.zafiro.voice.dictation.DictationTimer
import com.tricreta.zafiro.voice.dictation.EndReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous speech-to-text for the app. Thin Android facade over [ContinuousDictation] (which holds all the logic and is unit
 * tested). Create ONE per owner that outlives UI layout changes, and call [release] when that owner goes away.
 */
class VoiceInput(context: Context) {
    private val engine = AndroidSpeechEngine(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var dictation: ContinuousDictation? = null

    fun isAvailable(): Boolean = engine.isAvailable()

    val isActive: Boolean get() = dictation?.isActive == true

    /**
     * @param onTranscript the FULL transcript of this dictation on every change (not a delta).
     * @param onEnd why it ended; [EndReason.UserStopped] and [EndReason.Released] need no message to the user.
     */
    fun start(languageTag: String? = null, onTranscript: (String) -> Unit, onEnd: (EndReason) -> Unit) {
        if (isActive) return
        dictation = ContinuousDictation(
            engine = engine,
            timer = object : DictationTimer {
                override fun postDelayed(delayMs: Long, block: () -> Unit): DictationTimer.Handle {
                    val r = Runnable(block)
                    handler.postDelayed(r, delayMs)
                    return object : DictationTimer.Handle {
                        override fun cancel() = handler.removeCallbacks(r)
                    }
                }
            },
            onTranscript = onTranscript,
            onListening = { _listening.value = it },
            onEnd = onEnd,
            languageTag = languageTag,
        ).also { it.start() }
    }

    fun stop() {
        dictation?.stop()
    }

    fun release() {
        dictation?.release()
        dictation = null
        engine.release()
        _listening.value = false
    }
}
