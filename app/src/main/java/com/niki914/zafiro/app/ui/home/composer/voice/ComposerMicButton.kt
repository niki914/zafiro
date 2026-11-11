package com.niki914.zafiro.app.ui.home.composer.voice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.niki914.uikit.button.ZafiroActionButton

/** Pure UI. All dictation state lives in the [DictationController] owned by the composer, never in this button. */
@Composable
internal fun ComposerMicButton(
    controller: DictationController,
    modifier: Modifier = Modifier,
) {
    val listening = controller.listening
    val tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalContentColor provides tint) {
        ZafiroActionButton(
            onClick = controller::toggle,
            modifier = modifier,
        ) {
            Icon(
                imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (listening) "Stop voice input" else "Start voice input",
            )
        }
    }
}
