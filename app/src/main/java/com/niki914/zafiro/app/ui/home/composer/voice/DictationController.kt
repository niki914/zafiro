package com.niki914.zafiro.app.ui.home.composer.voice

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import com.tricreta.zafiro.voice.TranscriptMerger
import com.tricreta.zafiro.voice.VoiceInput
import com.tricreta.zafiro.voice.dictation.EndReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns dictation for the whole chat composer. It is created by the COMPOSER, above the compact/expanded layout switch, so it
 * survives that switch: the old design put the dictation object inside the mic button, and the button was destroyed the moment
 * the dictated text wrapped onto a second line.
 */
@Stable
class DictationController internal constructor(
    private val context: Context,
    private val voice: VoiceInput,
    private val scope: CoroutineScope,
    private val currentValue: () -> String,
    private val applyValue: (String) -> Unit,
    val listeningState: () -> Boolean,
) {
    val listening: Boolean get() = listeningState()

    fun toggle() {
        if (voice.isActive) {
            voice.stop()
            return
        }
        scope.launch {
            val pm = requireService<PermissionManager>()
            val granted = pm.status(Permission.MICROPHONE) == PermissionState.GRANTED ||
                pm.request(Permission.MICROPHONE).finalState == PermissionState.GRANTED
            if (!granted) {
                Toast.makeText(context, "Microphone permission is needed for voice input.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val base = currentValue()
            // The callback receives the FULL transcript each time, so it is always re-applied on top of what was typed
            // before the mic was tapped: later speech can never overwrite earlier speech.
            voice.start(
                onTranscript = { transcript -> applyValue(TranscriptMerger.merge(base, transcript)) },
                onEnd = { reason ->
                    when (reason) {
                        EndReason.UserStopped, EndReason.Released -> Unit
                        // Never end silently: tell the user why dictation stopped, so a problem can be diagnosed.
                        EndReason.SilenceTimeout ->
                            Toast.makeText(context, "Voice input stopped after a long silence. Tap the mic to continue.", Toast.LENGTH_SHORT).show()
                        is EndReason.Failed -> Toast.makeText(
                            context,
                            "${reason.message} (${reason.fault.name.lowercase()}" + (reason.code?.let { ", code $it" } ?: "") + ")",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
        }
    }
}

@Composable
fun rememberDictationController(value: String, onValueChange: (String) -> Unit): DictationController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { VoiceInput(context) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val listening by voice.listening.collectAsState()
    DisposableEffect(voice) { onDispose { voice.release() } }
    return remember(voice, scope) {
        DictationController(
            context = context,
            voice = voice,
            scope = scope,
            currentValue = { latestValue },
            applyValue = { latestOnChange(it) },
            listeningState = { voice.listening.value },
        )
    }.also { @Suppress("UNUSED_EXPRESSION") listening }
}
