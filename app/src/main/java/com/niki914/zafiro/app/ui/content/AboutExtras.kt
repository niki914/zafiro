package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.repo.ApkInstaller
import com.niki914.zafiro.repo.ReleaseInfo
import com.niki914.zafiro.repo.UpdateSource
import com.niki914.zafiro.repo.UpdateSources
import kotlinx.coroutines.launch

/** App logo with a slow breathing glow, the name, and who made it. */
@Composable
fun AboutHeader() {
    val context = LocalContext.current
    val icon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(256, 256).asImageBitmap() }.getOrNull() }
    val t = rememberInfiniteTransition(label = "logo")
    val glow by t.animateFloat(0.15f, 0.5f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "glow")
    val pulse by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().padding(top = 88.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(132.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = glow)))
            if (icon != null) Image(icon, contentDescription = "Zafiro Autonomous", modifier = Modifier.size(96.dp).scale(pulse).clip(CircleShape))
        }
        Text("Zafiro Autonomous", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("An AI team that works your phone day and night", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
        Text("by TricretA · built on open-source Zafiro (MIT)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Two update sources, side by side: this app (installable in place) and the original Zafiro (release notes and link only). */
@Composable
fun UpdateSourcesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val ours by produceState<ReleaseInfo?>(null) { value = UpdateSources.latest(UpdateSource.OURS, BuildConfig.VERSION_NAME) }
    val upstream by produceState<ReleaseInfo?>(null) { value = UpdateSources.latest(UpdateSource.UPSTREAM, "0.0.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You have ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                SourceCard(UpdateSource.OURS.title, ours, "Everything from the original Zafiro is merged into these releases.", canInstall = true, onInstall = { url ->
                    status = "Downloading..."
                    scope.launch { status = ApkInstaller.downloadAndInstall(context, url) ?: "Opening the installer..." }
                }, onOpen = { uri.openUri(it) })
                SourceCard(UpdateSource.UPSTREAM.title, upstream, "A separate app: it installs next to this one, not over it.", canInstall = false, onInstall = {}, onOpen = { uri.openUri(it) })
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SourceCard(title: String, info: ReleaseInfo?, hint: String, canInstall: Boolean, onInstall: (String) -> Unit, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when {
                info == null -> Text("Checking...", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("Latest: ${info.version}" + if (canInstall) (if (info.newer) "  ·  update available" else "  ·  you are up to date") else "", style = MaterialTheme.typography.labelLarge)
                    if (info.notes.isNotBlank()) {
                        Text("What's new", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            com.mikepenz.markdown.m3.Markdown(content = info.notes.take(2500))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canInstall && info.newer && info.apkUrl != null) Button(onClick = { onInstall(info.apkUrl) }, modifier = Modifier.height(48.dp)) { Text("Update now") }
                        if (info.pageUrl.isNotBlank()) OutlinedButton(onClick = { onOpen(info.pageUrl) }, modifier = Modifier.height(48.dp)) { Text("Release page") }
                    }
                }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
