package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.mikepenz.markdown.m3.Markdown
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.api.TeamService
import com.niki914.zafiro.repo.WorkspaceManager
import com.niki914.zafiro.service.findService
import com.tricreta.zafiro.autonomy.AutonomyGraph
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun spec(description: String?, vararg sections: SettingsSectionSpec) =
    SettingsPageSpec(description = description, sections = sections.filter { it.rows.isNotEmpty() })

private fun section(title: String, rows: List<SettingsRowSpec>) = SettingsSectionSpec(title = title, layout = SettingsSectionLayout.GroupedCard, rows = rows)

/** Plain-words reason for a failed or skipped run, so the owner does not have to read error codes. */
internal fun plainReason(state: String, reason: String?, error: String?): String {
    val e = (error.orEmpty() + " " + reason.orEmpty())
    return when {
        e.contains("Transport", true) -> "The model provider could not be reached"
        e.contains("Busy", true) -> "The agent was still busy with something else"
        state == "MISSED" -> "The phone or app was asleep at the scheduled time"
        state == "INTERRUPTED" || e.contains("process ended", true) -> "Android closed the app during the run"
        e.contains("keyguard", true) || e.contains("locked", true) -> "The phone was locked"
        e.contains("Shizuku", true) -> "Shizuku was not running"
        e.isBlank() -> ""
        else -> e.trim().take(120)
    }
}

// ------------------------------------------------------------------------------------------------ Workspaces

@Composable
fun WorkspacesPageContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defs = remember { WorkspaceManager.defs(context) }
    val states by AutonomyGraph.require().repo.teamDao().observeWorkspaces().collectAsState(initial = emptyList())
    val on = states.filter { it.enabled }.map { it.id }.toSet()
    val s = spec(
        "Each workspace is a ready-made team for one platform: agents with roles, scheduled tasks, skills, rules and screen routines. Switch one on and it works day and night until you switch it off. Start with the platforms you actually use.",
        section("Workspaces", defs.map { d ->
            SettingsRowSpec.Toggle(id = d.id, title = d.name, summary = "${d.agents.size} agents · ${d.tasks.size} tasks · ${d.summary.substringAfter(": ", d.summary).take(80)}", checked = d.id in on)
        }),
    )
    SettingsSpecPageContent(spec = s, onAction = { a ->
        if (a is SettingsRowAction.ToggleChanged) {
            val def = defs.firstOrNull { it.id == a.id } ?: return@SettingsSpecPageContent
            scope.launch {
                val msg = runCatching { WorkspaceManager.setEnabled(context, def, a.checked) }.getOrElse { "Could not switch ${def.name}: ${it.message}" }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    })
}

// ------------------------------------------------------------------------------------------------ Dashboard

@Composable
fun DashboardPageContent(onOpenFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = AutonomyGraph.require()
    val dao = graph.repo.teamDao()
    val pending by dao.observePendingActions().collectAsState(initial = emptyList())
    val actions by dao.observeActions(25).collectAsState(initial = emptyList())
    val board by dao.observeBoard(40).collectAsState(initial = emptyList())
    val runs by graph.repo.observeRuns(25).collectAsState(initial = emptyList())
    val workspaces by dao.observeWorkspaces().collectAsState(initial = emptyList())
    val names = remember { WorkspaceManager.defs(context).associate { it.id to it.name } }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    fun ago(ms: Long) = time.format(Date(ms))
    val decide: (Long, Boolean) -> Unit = { id, approve ->
        scope.launch {
            val r = findService<TeamService>()?.run("engage", mapOf("op" to if (approve) "approve" else "reject", "id" to id.toString(), "reason" to if (approve) "" else "rejected by owner"))
            Toast.makeText(context, if (r?.ok == true) (if (approve) "Approved" else "Rejected") else (r?.text ?: "Team service is not running"), Toast.LENGTH_SHORT).show()
        }
    }

    val running = runs.count { it.state == "RUNNING" || it.state == "STARTING" }
    val failed = runs.take(10).count { it.state in setOf("FAILED", "INTERRUPTED", "MISSED") }
    val s = spec(
        "What the team is doing, what needs you, and what went wrong. Updates live.",
        section("Needs you (${pending.size})", pending.flatMap { p ->
            listOf(
                SettingsRowSpec.Action(id = "ok:${p.id}", title = "Approve #${p.id}: ${p.action} on ${names[p.workspaceId] ?: p.platform}", summary = p.text.take(240).ifBlank { p.target }),
                SettingsRowSpec.Destructive(id = "no:${p.id}", title = "Reject #${p.id}"),
            )
        }),
        section("Workspaces", workspaces.filter { it.enabled }.map { w ->
            val open = board.count { it.workspaceId == w.id && it.status in setOf("TODO", "DOING", "BLOCKED") }
            val done = actions.count { it.workspaceId == w.id && it.status == "DONE" }
            SettingsRowSpec.Value(title = names[w.id] ?: w.id, summary = "$open open on the board · $done actions done recently", currentState = "ON")
        }.ifEmpty { listOf(SettingsRowSpec.Message("No workspace is on yet. Open Workspaces and switch one on.")) } +
            workspaces.filter { it.enabled && it.id != "toolsmith" && it.id != "health" }.map { w ->
                SettingsRowSpec.Action(id = "run:${w.id}", title = "Run ${names[w.id] ?: w.id} now", summary = "Starts the Director. It keeps handing work down the team until the board is empty or today's caps are reached.")
            }),
        section("Right now ($running running, $failed of the last 10 runs failed)", runs.take(12).map { r ->
            SettingsRowSpec.Value(title = r.taskName, summary = listOf(ago(r.createdAtMs), "${r.steps} steps", plainReason(r.state, r.reason, r.error)).filter { it.isNotBlank() }.joinToString(" · "), currentState = r.state)
        }),
        section("Team board", board.take(20).map { b ->
            SettingsRowSpec.Value(title = b.title.take(80), summary = "${b.ownerAgent.ifBlank { "unassigned" }}${if (b.result.isNotBlank()) " · " + b.result.take(100) else ""}", currentState = b.status)
        }),
        section("Recent actions", actions.take(15).map { a ->
            SettingsRowSpec.Value(title = "${a.action} · ${names[a.workspaceId] ?: a.platform}", summary = (a.text.ifBlank { a.target }).take(120), currentState = a.status)
        }),
        section("More", listOf(SettingsRowSpec.Navigation(id = "files", title = "Files made by the agents", summary = "Plans, drafts, reports and images"))),
    )
    SettingsSpecPageContent(spec = s, onAction = { a ->
        when {
            a is SettingsRowAction.Click && a.id.startsWith("ok:") -> decide(a.id.removePrefix("ok:").toLong(), true)
            a is SettingsRowAction.Click && a.id.startsWith("no:") -> decide(a.id.removePrefix("no:").toLong(), false)
            a is SettingsRowAction.Navigate && a.id == "files" -> onOpenFiles()
            a is SettingsRowAction.Click && a.id.startsWith("run:") -> scope.launch {
                val id = com.tricreta.zafiro.autonomy.workspace.WorkspaceInstaller.taskId(a.id.removePrefix("run:"), "heartbeat")
                val ok = runCatching { graph.repo.enqueueManual(id); graph.scheduler.reconcile("run_now") }.isSuccess
                Toast.makeText(context, if (ok) "Started. Watch the Dashboard." else "Could not start it.", Toast.LENGTH_SHORT).show()
            }
        }
    })
}

// ------------------------------------------------------------------------------------------------ Files

internal fun outputsRoot(context: android.content.Context) = File(context.filesDir, "outputs")

internal fun isTextLike(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt", "log", "json", "csv", "html", "htm")
internal fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

@Composable
fun FilesPageContent(onOpen: (relative: String) -> Unit) {
    val context = LocalContext.current
    val root = remember { outputsRoot(context) }
    var refresh by remember { mutableStateOf(0) }
    val files by produceState(initialValue = emptyList<File>(), refresh) {
        value = withContextIo { root.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(300).toList() }
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val s = spec(
        if (files.isEmpty()) "Nothing here yet. When an agent saves a plan, draft, report or image it appears here and as a tappable link in the chat." else "Everything the agents have saved. Tap to open.",
        section("Files", files.map { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            SettingsRowSpec.Navigation(id = rel, title = rel, summary = "${fmt.format(Date(f.lastModified()))} · ${f.length() / 1024 + 1} KB")
        }),
    )
    SettingsSpecPageContent(spec = s, onAction = { a -> if (a is SettingsRowAction.Navigate) onOpen(a.id) })
}

private suspend fun <T> withContextIo(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

/** Opens any saved file: Markdown, HTML, text and images in the app; everything else in whichever app can open it. */
@Composable
fun FileViewPageContent(relative: String, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val root = remember { outputsRoot(context) }
    val file = remember(relative) { File(root, relative).takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) && it.isFile } }
    if (file == null) {
        Text("This file no longer exists.", modifier = Modifier.padding(24.dp))
        return
    }
    val ext = file.extension.lowercase()
    val uri = remember(file) { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }
    val mime = remember(file) { com.niki914.zafiro.chat.agentic.buildin.impl.OutputPaths.mime(file.name) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
            }) { Text("Share") }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    .onFailure { Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show() }
            }) { Text("Open with") }
            OutlinedButton(onClick = { if (file.delete()) onDeleted() }) { Text("Delete") }
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            when {
                ext == "html" || ext == "htm" -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c -> android.webkit.WebView(c).apply { settings.javaScriptEnabled = false; loadDataWithBaseURL(null, file.readText(), "text/html", "utf-8", null) } },
                )
                ext == "md" || ext == "markdown" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { Markdown(content = file.readText()) }
                isImage(file.name) -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c -> android.widget.ImageView(c).apply { setImageURI(uri); adjustViewBounds = true } },
                )
                isTextLike(file.name) -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(file.readText().take(200_000), style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                }
                else -> Text("This file type opens in another app. Tap \"Open with\".", modifier = Modifier.padding(24.dp))
            }
        }
    }
}
