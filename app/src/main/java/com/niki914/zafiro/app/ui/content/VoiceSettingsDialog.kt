package com.niki914.zafiro.app.ui.content

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.repo.VoiceEngine
import com.niki914.zafiro.repo.VoiceSettings
import com.niki914.zafiro.repo.VoiceSettingsData

/** Choose the speech-to-text engine: the phone's free recogniser, or Groq/OpenAI/custom Whisper with an API key. */
@Composable
fun VoiceSettingsDialog(context: Context, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(VoiceSettings.read(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice input") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VoiceEngine.entries.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { d = d.copy(engine = e) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = d.engine == e, onClick = { d = d.copy(engine = e) })
                        Text(e.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (d.engine == VoiceEngine.DEVICE) {
                    Text("Uses Google's speech service on this phone. It can beep and restart between sentences on some phones; a cloud engine below is steadier.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("Tap the mic once to start recording and again to stop: the audio is then sent to the service and the text appears.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(value = d.apiKey, onValueChange = { d = d.copy(apiKey = it) }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (d.engine == VoiceEngine.CUSTOM) {
                        OutlinedTextField(value = d.baseUrl, onValueChange = { d = d.copy(baseUrl = it) }, label = { Text("Base URL (ends with /v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    OutlinedTextField(value = d.model, onValueChange = { d = d.copy(model = it) }, label = { Text("Model (blank = default)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = d.language, onValueChange = { d = d.copy(language = it) }, label = { Text("Language code, e.g. en or sw (blank = auto)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (d.apiKey.isBlank()) Text("Without a key the phone's own recogniser is used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { VoiceSettings.write(context, d); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun voiceSummary(context: Context): String {
    val d: VoiceSettingsData = VoiceSettings.read(context)
    return if (d.cloudConfig() != null) d.engine.label.substringBefore(" (") else "This phone (free)"
}
