package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
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

/** Space the page chrome (status bar + title/back bar) takes at the top; custom pages draw edge to edge, so they must start below it. */
@Composable
internal fun topChromePadding(): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() } + 68.dp

/** Space the system navigation bar takes at the bottom. */
@Composable
internal fun bottomChromePadding(): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() } + 8.dp

private fun spec(description: String, vararg sections: SettingsSectionSpec) =
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

    val body = androidx.compose.material3.MaterialTheme.typography.bodyLarge
    val md = com.mikepenz.markdown.m3.markdownTypography(
        h1 = body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        h2 = body.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        h3 = body.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    )
    Column(Modifier.fillMaxSize()) {
        Text(file.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(start = 76.dp, end = 16.dp, top = topChromePadding() - 56.dp, bottom = 8.dp))
        Column(Modifier.weight(1f).fillMaxSize()) {
            when {
                ext == "html" || ext == "htm" -> WebBlock(file.readText())
                ext == "svg" -> WebBlock("<html><body style='margin:0;background:#fff'>" + file.readText() + "</body></html>")
                ext == "md" || ext == "markdown" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { Markdown(content = file.readText(), typography = md) }
                isImage(file.name) -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> android.widget.ImageView(c).apply { setImageURI(uri); adjustViewBounds = true } })
                ext == "pdf" -> PdfPages(file)
                ext in MEDIA_EXT -> MediaBlock(uri, audio = ext in AUDIO_EXT)
                ext == "csv" || ext == "tsv" -> CsvTable(file.readText())
                ext == "json" -> TextBlock(runCatching { kotlinx.serialization.json.Json { prettyPrint = true }.let { j -> j.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), j.parseToJsonElement(file.readText())) } }.getOrElse { file.readText() })
                ext in OFFICE_EXT -> TextBlock(FileText.office(file) ?: FileText.bytesPreview(file))
                ext in ARCHIVE_EXT -> TextBlock(FileText.archiveListing(file)?.joinToString("\n") ?: FileText.bytesPreview(file))
                isTextLike(file.name) -> TextBlock(file.readText().take(200_000))
                else -> TextBlock(FileText.bytesPreview(file))
            }
        }
        androidx.compose.material3.Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + bottomChromePadding()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button(modifier = Modifier.weight(1f).height(52.dp), onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                }) { Text("Share") }
                OutlinedButton(modifier = Modifier.weight(1f).height(52.dp), onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show() }
                }) { Text("Open with") }
                OutlinedButton(modifier = Modifier.weight(1f).height(52.dp), onClick = { if (file.delete()) onDeleted() }) { Text("Delete") }
            }
        }
    }
}


private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus")
private val MEDIA_EXT = AUDIO_EXT + setOf("mp4", "3gp", "webm", "mkv", "mov")
private val OFFICE_EXT = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
private val ARCHIVE_EXT = setOf("zip", "apk", "jar", "aar")

@Composable
private fun TextBlock(text: String) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun WebBlock(html: String) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        android.webkit.WebView(c).apply { settings.javaScriptEnabled = false; loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    })
}

@Composable
private fun CsvTable(text: String) {
    val rows = remember(text) { FileText.csvRows(text) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        itemsIndexed(rows) { i, r ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                r.forEach { cell -> Text(cell, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), style = if (i == 0) androidx.compose.material3.MaterialTheme.typography.labelLarge else androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 4) }
            }
            androidx.compose.material3.HorizontalDivider()
        }
    }
}

@Composable
private fun MediaBlock(uri: android.net.Uri, audio: Boolean) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        android.widget.VideoView(c).apply {
            val mc = android.widget.MediaController(c).also { it.setAnchorView(this) }
            setMediaController(mc); setVideoURI(uri); setOnPreparedListener { start() }
        }
    })
}

/** Renders PDF pages to bitmaps one at a time, so a long document never sits in memory whole. */
@Composable
private fun PdfPages(file: File) {
    val context = LocalContext.current
    val widthPx = context.resources.displayMetrics.widthPixels
    val renderer = remember(file) { runCatching { android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    androidx.compose.runtime.DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { Text("This PDF could not be opened.", Modifier.padding(24.dp)); return }
    val lock = remember { Any() }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        items(renderer.pageCount) { i ->
            val bmp by produceState<android.graphics.Bitmap?>(null, i) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    synchronized(lock) {
                        runCatching {
                            renderer.openPage(i).use { p ->
                                val h = (widthPx.toFloat() * p.height / p.width).toInt()
                                android.graphics.Bitmap.createBitmap(widthPx, h, android.graphics.Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            }
                        }.getOrNull()
                    }
                }
            }
            bmp?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Page ${i + 1}", modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
                ?: androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(400.dp))
        }
    }
}
