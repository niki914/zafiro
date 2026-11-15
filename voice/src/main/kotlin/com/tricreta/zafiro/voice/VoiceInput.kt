package com.tricreta.zafiro.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tricreta.zafiro.voice.dictation.ContinuousDictation
import com.tricreta.zafiro.voice.dictation.DictationTimer
import com.tricreta.zafiro.voice.dictation.EndReason
import com.tricreta.zafiro.voice.dictation.DictationSession.Fault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous speech-to-text for the app. Thin Android facade over [ContinuousDictation] (which holds all the logic and is unit
 * tested). Create ONE per owner that outlives UI layout changes, and call [release] when that owner goes away.
 */
class VoiceInput(context: Context, private val cloud: () -> CloudSttConfig? = { null }) {
    private val appContext = context.applicationContext
    private val recorder = CloudRecorder(appContext)
    private var cloudJob: Thread? = null
    private var cloudEnd: ((EndReason) -> Unit)? = null
    private var cloudTranscript: ((String) -> Unit)? = null
    private var cloudActive = false
    private val engine = AndroidSpeechEngine(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var dictation: ContinuousDictation? = null

    fun isAvailable(): Boolean = engine.isAvailable()

    val isActive: Boolean get() = cloudActive || dictation?.isActive == true

    /**
     * @param onTranscript the FULL transcript of this dictation on every change (not a delta).
     * @param onEnd why it ended; [EndReason.UserStopped] and [EndReason.Released] need no message to the user.
     */
    fun start(languageTag: String? = null, onTranscript: (String) -> Unit, onEnd: (EndReason) -> Unit) {
        if (isActive) return
        val cfg = cloud()
        if (cfg != null) { startCloud(cfg, onTranscript, onEnd); return }
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

    private fun startCloud(cfg: CloudSttConfig, onTranscript: (String) -> Unit, onEnd: (EndReason) -> Unit) {
        runCatching { recorder.start() }.onFailure {
            onEnd(EndReason.Failed(Fault.FATAL, null, "Could not start the microphone: ${it.message}")); return
        }
        cloudActive = true; cloudEnd = onEnd; cloudTranscript = onTranscript
        _listening.value = true
        // Safety stop so a forgotten recording does not run (and upload) forever.
        handler.postDelayed({ if (cloudActive) stop() }, MAX_RECORD_MS)
    }

    private fun finishCloud(cfg: CloudSttConfig?) {
        val onEnd = cloudEnd; val onText = cloudTranscript
        cloudActive = false; cloudEnd = null; cloudTranscript = null
        _listening.value = false
        val file = recorder.stop()
        if (cfg == null || file == null) { onEnd?.invoke(EndReason.Failed(Fault.NO_SPEECH, null, "Nothing was recorded. Hold on a little longer, then tap stop.")); return }
        cloudJob = Thread {
            val result = CloudStt.transcribe(cfg, file)
            file.delete()
            handler.post {
                result.onSuccess { t -> if (t.isBlank()) onEnd?.invoke(EndReason.Failed(Fault.NO_SPEECH, null, "No speech was recognised.")) else { onText?.invoke(t); onEnd?.invoke(EndReason.UserStopped) } }
                    .onFailure { e -> onEnd?.invoke(EndReason.Failed(Fault.TRANSIENT, null, e.message ?: "Speech service failed.")) }
            }
        }.also { it.start() }
    }

    fun stop() {
        if (cloudActive) { finishCloud(cloud()); return }
        dictation?.stop()
    }

    fun release() {
        if (cloudActive) { cloudActive = false; recorder.cancel() }
        dictation?.release()
        dictation = null
        engine.release()
        _listening.value = false
    }

    private companion object { const val MAX_RECORD_MS = 120_000L }
}
