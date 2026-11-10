package com.niki914.zafiro.app.ui.home.composer.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.niki914.uikit.button.ZafiroActionButton
import com.tricreta.zafiro.voice.SpeechState
import com.tricreta.zafiro.voice.TranscriptMerger
import com.tricreta.zafiro.voice.VoiceInput

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

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else Toast.makeText(context, "Microphone permission is needed for voice input.", Toast.LENGTH_SHORT).show()
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
            onClick = {
                when {
                    listening -> voice.stop()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = modifier,
        ) {
            Icon(
                imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (listening) "Stop voice input" else "Start voice input",
            )
        }
    }
}
