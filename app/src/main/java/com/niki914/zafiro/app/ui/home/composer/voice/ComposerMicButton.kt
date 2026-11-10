package com.niki914.zafiro.app.ui.home.composer.voice

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.niki914.uikit.button.ZafiroActionButton
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import com.tricreta.zafiro.voice.SpeechState
import com.tricreta.zafiro.voice.TranscriptMerger
import com.tricreta.zafiro.voice.VoiceInput
import kotlinx.coroutines.launch

/**
 * Dictation button for the chat composer. Tap to start, tap again to finish. Live partial text appears in the input box
 * (appended to whatever was already typed); the final transcript replaces the partial one. Nothing is sent automatically.
 */
@Composable
internal fun ComposerMicButton(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val voice = remember { VoiceInput(context) }
    val state by voice.state.collectAsState()
    var baseText by remember { mutableStateOf("") }
    val listening = state is SpeechState.Listening

    DisposableEffect(voice) { onDispose { voice.release() } }

    // Keep the latest callbacks without restarting the recogniser.
    val latestOnChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)

    fun begin() {
        baseText = value
        voice.start { finalText -> latestOnChange(TranscriptMerger.merge(baseText, finalText)) }
    }

    val scope = rememberCoroutineScope()

    // Microphone access goes through PermissionManager like every other permission (root/Shizuku can grant it silently).
    fun beginWithPermission() {
        scope.launch {
            val pm = requireService<PermissionManager>()
            val granted = pm.status(Permission.MICROPHONE) == PermissionState.GRANTED ||
                pm.request(Permission.MICROPHONE).finalState == PermissionState.GRANTED
            if (granted) {
                begin()
            } else {
                Toast.makeText(context, "Microphone permission is needed for voice input.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Live partial transcript.
    LaunchedEffect(state) {
        (state as? SpeechState.Listening)?.partial?.takeIf { it.isNotBlank() }?.let {
            latestOnChange(TranscriptMerger.merge(baseText, it))
        }
        (state as? SpeechState.Failed)?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            voice.clearError()
        }
    }

    val tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalContentColor provides tint) {
        ZafiroActionButton(
            onClick = { if (listening) voice.stop() else beginWithPermission() },
            modifier = modifier,
        ) {
            Icon(
                imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (listening) "Stop voice input" else "Start voice input",
            )
        }
    }
}
