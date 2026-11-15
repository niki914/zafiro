package com.niki914.zafiro.app.ui.content

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.repo.WorkspaceManager
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.workspace.WorkspaceDef
import kotlinx.coroutines.launch

/** One card per platform: its icon, a switch, and a bin. Nothing to read. */
@Composable
fun WorkspacesVisualContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val defs = remember(tick) { WorkspaceManager.defs(context) }
    val deleted = remember(tick) { WorkspaceManager.deletedIds(context) }
    val states by AutonomyGraph.require().repo.teamDao().observeWorkspaces().collectAsState(initial = emptyList())
    val on = states.filter { it.enabled }.map { it.id }.toSet()
    var confirm by remember { mutableStateOf<WorkspaceDef?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = topChromePadding(), bottom = 24.dp + bottomChromePadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${on.size} on", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        items(defs, key = { it.id }) { d ->
            val isOn = d.id in on
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlatformIcon(d.platform, d.app)
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${d.agents.size} agents · ${d.tasks.size} tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirm = d }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${d.name}") }
                    Switch(checked = isOn, onCheckedChange = { want ->
                        scope.launch {
                            val msg = runCatching { WorkspaceManager.setEnabled(context, d, want) }.getOrElse { "Could not switch ${d.name}: ${it.message}" }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
        if (deleted.isNotEmpty()) item {
            OutlinedButton(onClick = { WorkspaceManager.restoreDeleted(context); tick++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Restore ${deleted.size} deleted") }
        }
    }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text("Its agents, tasks and history are removed. You can restore it later.") },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { WorkspaceManager.delete(context, d); tick++ } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
