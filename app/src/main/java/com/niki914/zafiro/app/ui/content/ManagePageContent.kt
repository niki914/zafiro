package com.niki914.zafiro.app.ui.content

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.ResetKind
import com.niki914.zafiro.repo.ResetManager
import com.niki914.zafiro.repo.XRepo
import com.tricreta.zafiro.autonomy.AutonomyGraph
import kotlinx.coroutines.launch
import java.io.File

/** One removable thing in a list: [editable] items can have their text changed, [note] marks things that ship with the app. */
internal data class ManageItem(val id: String, val title: String, val subtitle: String, val note: String = "", val editable: Boolean = false, val full: String = "")

private val TABS = listOf("Teams", "Tasks", "Agents", "Skills", "MCP", "Memory", "Files", "Reset")

/** Delete or edit what is stored, in plain words (not file names), and reset the app to its defaults, choosing exactly what goes. */
@Composable
fun ManagePageContent() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(top = topChromePadding())) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (TABS[tab]) {
            "Teams" -> TeamsTab()
            "Tasks" -> TasksTab()
            "Agents" -> AgentsTab()
            "Skills" -> SkillsTab()
            "MCP" -> McpTab()
            "Memory" -> MemoryTab()
            "Files" -> FilesTab()
            else -> ResetTab()
        }
    }
}

@Composable
private fun TeamsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = com.niki914.zafiro.repo.WorkspaceManager.defs(context).map { ManageItem(it.id, it.name, "${it.agents.size} agents, ${it.tasks.size} tasks") }
    }
    SelectList(items, "Delete teams", onDelete = { ids ->
        scope.launch {
            com.niki914.zafiro.repo.WorkspaceManager.defs(context).filter { it.id in ids }.forEach { com.niki914.zafiro.repo.WorkspaceManager.delete(context, it) }
            refresh++
        }
    })
}

@Composable
private fun TasksTab() {
    val repo = AutonomyGraph.require().repo
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = repo.tasks().sortedBy { it.name.lowercase() }.map {
            ManageItem(it.id, it.name, (if (it.enabled) "on" else "off") + " · " + (it.timeOfDay ?: it.triggerType), note = if (it.workspaceId.isEmpty()) "custom" else it.workspaceId.removePrefix("legacy:").let { w -> if (it.workspaceId.startsWith("legacy:")) "old $w task" else w })
        }
    }
    SelectList(items, "Delete tasks", onDelete = { ids ->
        scope.launch {
            ids.forEach { repo.deleteTask(it) }
            AutonomyGraph.require().scheduler.reconcile("bulk_delete"); refresh++
        }
    })
}

@Composable
private fun AgentsTab() {
    val repo = AutonomyGraph.require().repo
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = repo.agents().map { ManageItem(it.id, it.name, it.instructions.lineSequence().firstOrNull { l -> l.isNotBlank() }.orEmpty().take(110), note = if (it.workspaceId.isEmpty()) "custom" else it.workspaceId) }
    }
    SelectList(items, "Delete agents", onDelete = { ids -> scope.launch { ids.forEach { repo.deleteAgent(it) }; refresh++ } })
}

@Composable
private fun SkillsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val bundled = remember { context.assets.list("skills").orEmpty().toSet() }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = XRepo.skills.listAll().sortedBy { it.name.lowercase() }.map { ManageItem(it.id, it.name, it.description.take(110), note = if (it.id in bundled) "came with app" else "custom") }
    }
    SelectList(items, "Delete skills", onDelete = { ids -> scope.launch { ids.forEach { runCatching { XRepo.skills.delete(it) } }; refresh++ } })
}

@Composable
private fun McpTab() {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = XRepo.mcp.list().map { ManageItem(it.name, it.name, it.url + if (it.enabled) " · on" else " · off") }
    }
    SelectList(items, "Delete MCP servers", onDelete = { names ->
        scope.launch {
            XRepo.updateJsonOrFalse(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
                com.niki914.zafiro.repo.McpSettingsCodec.encodeServers(com.niki914.zafiro.repo.McpSettingsCodec.parseServers(json).filter { it.name !in names })
            }
            refresh++
        }
    })
}

@Composable
private fun MemoryTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        val main = XRepo.memory.list().mapIndexed { i, t -> ManageItem("main#$i", com.niki914.zafiro.repo.MemoryTitle.of(t), t.take(110), note = "main", editable = true, full = t) }
        val others = ResetManager.agentMemories(context).map { ManageItem("${it.agent}#${it.index}", com.niki914.zafiro.repo.MemoryTitle.of(it.text), it.text.take(110), note = it.agent) }
        value = main + others
    }
    var editing by remember { mutableStateOf<ManageItem?>(null) }
    var draft by remember { mutableStateOf("") }
    SelectList(items, "Delete memories", onEdit = { editing = it; draft = it.full.ifEmpty { it.title } }, onDelete = { ids ->
        scope.launch {
            // Delete from the highest index down so earlier indexes stay valid.
            ids.sortedByDescending { it.substringAfter('#').toIntOrNull() ?: 0 }.forEach { id ->
                val agent = id.substringBefore('#'); val index = id.substringAfter('#').toInt()
                if (agent == "main") XRepo.memory.delete(index) else ResetManager.deleteAgentMemory(context, agent, index)
            }
            refresh++
        }
    })
    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { scope.launch { XRepo.memory.update(e.id.substringAfter('#').toInt(), draft.trim()); editing = null; refresh++ } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val root = remember { File(context.filesDir, "outputs") }
    val items by produceState(emptyList<ManageItem>(), refresh) {
        value = root.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(300).map {
            ManageItem(it.relativeTo(root).path, it.relativeTo(root).path, "${it.length() / 1024 + 1} KB")
        }.toList()
    }
    SelectList(items, "Delete files", onDelete = { ids -> scope.launch { ids.forEach { File(root, it).delete() }; refresh++ } })
}

@Composable
private fun ResetTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(setOf<ResetKind>()) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomChromePadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reset to defaults", style = MaterialTheme.typography.titleLarge)
        Text("Tick only what should go. Everything that came with the app (bundled skills, workspaces, MCP servers) stays, and your model configurations are never touched.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(ResetKind.entries.toList()) { k ->
                Row(Modifier.fillMaxWidth().clickable { picked = if (k in picked) picked - k else picked + k }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = k in picked, onCheckedChange = { picked = if (it) picked + k else picked - k })
                    Column(Modifier.padding(start = 8.dp)) { Text(k.title, style = MaterialTheme.typography.titleMedium); Text(k.summary, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picked = if (picked.size == ResetKind.entries.size) emptySet() else ResetKind.entries.toSet() }, modifier = Modifier.weight(1f)) { Text("Select all") }
            Button(onClick = { confirm = true }, enabled = picked.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) { Text("Reset ${picked.size}") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove these?") },
            text = { Text(picked.joinToString("\n") { "• " + it.title } + "\n\nThis cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = false; scope.launch { Toast.makeText(context, ResetManager.reset(context, picked), Toast.LENGTH_LONG).show(); picked = emptySet() } }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Checkbox list with select-all and a big Delete button at the bottom (thumb reach). */
@Composable
internal fun SelectList(items: List<ManageItem>, deleteLabel: String, onDelete: (List<String>) -> Unit, onEdit: ((ManageItem) -> Unit)? = null) {
    var selected by remember(items) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Nothing here.") }
        else LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { it0 ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (it0.id in selected) selected - it0.id else selected + it0.id }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = it0.id in selected, onCheckedChange = { c -> selected = if (c) selected + it0.id else selected - it0.id })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(it0.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(it0.subtitle, it0.note).filter { s -> s.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (onEdit != null && it0.editable) TextButton(onClick = { onEdit(it0) }) { Text("Edit") }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + bottomChromePadding()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet() }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Text(if (selected.size == items.size && items.isNotEmpty()) "Select none" else "Select all (${items.size})")
                }
                Button(onClick = { confirm = true }, enabled = selected.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Text("$deleteLabel (${selected.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selected.size} item(s)?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(selected.toList()) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
