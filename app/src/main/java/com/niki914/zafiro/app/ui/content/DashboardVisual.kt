package com.niki914.zafiro.app.ui.content

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.api.TeamService
import com.niki914.zafiro.repo.WorkspaceManager
import com.niki914.zafiro.service.findService
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.data.ActionLogEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

private val Good = Color(0xFF2E9E6B)
private val Bad = Color(0xFFD64545)
private val Warn = Color(0xFFE0A030)
private val Info = Color(0xFF3B7DDD)

/** Approve or reject one pending action, optionally with the owner's opinion; the opinion is saved as a labelled example. */
internal suspend fun decideAction(id: Long, approve: Boolean, reason: String): String {
    val r = findService<TeamService>()?.run("engage", mapOf("op" to if (approve) "approve" else "reject", "id" to id.toString(), "reason" to reason))
    return if (r?.ok == true) (if (approve) "Approved" else "Rejected") else (r?.text ?: "The team service is not running")
}

/** The card used both on the Dashboard and in the global pop-up. */
@Composable
internal fun ApprovalCard(a: ActionLogEntity, workspaceName: String, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Boolean?>(null) }
    var note by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$workspaceName wants to ${a.action}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (a.target.isNotBlank()) Text("To: ${a.target}", style = MaterialTheme.typography.bodyMedium)
            Text(a.text.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 12, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { scope.launch { onDone(decideAction(a.id, true, "")) } }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Approve") }
                OutlinedButton(onClick = { asking = false }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Reject") }
            }
            TextButton(onClick = { asking = true }) { Text("Approve with a note...") }
        }
    }
    asking?.let { approve ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (approve) "Approve with a note" else "Why reject? (optional)") },
            text = { OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("Your opinion helps the team learn") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { val n = note; note = ""; asking = null; scope.launch { onDone(decideAction(a.id, approve, n)) } }) { Text(if (approve) "Approve" else "Reject") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SuccessRing(good: Int, bad: Int, modifier: Modifier = Modifier) {
    val total = (good + bad).coerceAtLeast(1)
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val inset = w / 2
            val sz = Size(size.width - w, size.height - w)
            drawArc(Color.Gray.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            if (good + bad > 0) {
                drawArc(Good, -90f, 360f * good / total, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                if (bad > 0) drawArc(Bad, -90f + 360f * good / total, 360f * bad / total, false, Offset(inset, inset), sz, style = Stroke(w))
            }
        }
        Text(if (good + bad == 0) "-" else "${100 * good / total}%", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun BarChart(values: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val max = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { (label, v) ->
            Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("$v", style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                    drawRoundRect(track, Offset.Zero, size, CornerRadius(8.dp.toPx()))
                    val h = size.height * v / max
                    drawRoundRect(bar, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(8.dp.toPx()))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun roleColor(name: String): Color = listOf(Info, Good, Warn, Color(0xFF8E5BD6), Color(0xFFD6658E), Color(0xFF2FA3B5))[(name.lowercase().hashCode() and 0x7fffffff) % 6]

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Detail(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() } },
        confirmButton = { Row { actions(); TextButton(onClick = onClose) { Text("Close") } } },
    )
}

@Composable
fun DashboardVisualContent(onOpenFiles: () -> Unit, onOpenWorkspaces: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = AutonomyGraph.require()
    val dao = graph.repo.teamDao()
    val pending by dao.observePendingActions().collectAsState(initial = emptyList())
    val actions by dao.observeActions(40).collectAsState(initial = emptyList())
    val board by dao.observeBoard(120).collectAsState(initial = emptyList())
    val runs by graph.repo.observeRuns(60).collectAsState(initial = emptyList())
    val agents by graph.repo.observeAgents().collectAsState(initial = emptyList())
    val workspaces by dao.observeWorkspaces().collectAsState(initial = emptyList())
    val byDay by dao.observeDoneByDay(7).collectAsState(initial = emptyList())
    val today = LocalDate.now().toString()
    val names = remember { WorkspaceManager.defs(context).associate { it.id to it.name } }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    val doneToday = byDay.firstOrNull { it.day == today }?.n ?: 0
    val recent = runs.take(30)
    val good = recent.count { it.state == "SUCCEEDED" }
    val bad = recent.count { it.state in setOf("FAILED", "INTERRUPTED", "MISSED", "LIMIT_REACHED") }
    val running = runs.count { it.state == "RUNNING" || it.state == "STARTING" }
    val doing = board.filter { it.status == "DOING" }.map { it.ownerAgent.lowercase() }.toSet()
    val week = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }.map { d -> d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } to (byDay.firstOrNull { it.day == d.toString() }?.n ?: 0) }
    val hasData = week.any { it.second > 0 }

    var filter by remember { mutableStateOf("All") }
    var boardDetail by remember { mutableStateOf<String?>(null) }
    var agentDetail by remember { mutableStateOf<com.tricreta.zafiro.autonomy.data.AgentProfileEntity?>(null) }
    var runDetail by remember { mutableStateOf<com.tricreta.zafiro.autonomy.data.RunEntity?>(null) }
    var confirmPause by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(androidx.compose.material.icons.Icons.Default.PlayArrow, "Run now", Good) {
                    scope.launch {
                        val on = workspaces.filter { it.enabled && it.id != "toolsmith" && it.id != "health" }
                        if (on.isEmpty()) { toast("Switch a team on first"); onOpenWorkspaces(); return@launch }
                        on.forEach { runCatching { graph.repo.enqueueManual(com.tricreta.zafiro.autonomy.workspace.WorkspaceInstaller.taskId(it.id, "heartbeat")) } }
                        runCatching { graph.scheduler.reconcile("run_now") }; toast("Started ${on.size} team(s)")
                    }
                }
                RoundAction(androidx.compose.material.icons.Icons.Default.Pause, "Pause all", Warn) { confirmPause = true }
                RoundAction(androidx.compose.material.icons.Icons.Default.Groups, "Teams", Info, onOpenWorkspaces)
                RoundAction(androidx.compose.material.icons.Icons.Default.Folder, "Files", Color(0xFF8E5BD6), onOpenFiles)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (running > 0) "● working" else "○ idle", style = MaterialTheme.typography.labelMedium, color = if (running > 0) Good else MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("$doneToday", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text("done today", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(Modifier.weight(1f).clickable { filter = "Failed" }) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) { SuccessRing(good, bad); Text("success", style = MaterialTheme.typography.labelSmall) }
                }
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = if (pending.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else Warn.copy(alpha = 0.3f))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${pending.size}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text("need you", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (workspaces.none { it.enabled }) item {
            Button(onClick = onOpenWorkspaces, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Switch a team on") }
        }
        if (pending.isNotEmpty()) items(pending, key = { "p${it.id}" }) { ApprovalCard(it, names[it.workspaceId] ?: it.platform, toast) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(16.dp)) {
                    BarChart(week)
                    if (!hasData) Text("Charts fill up as the team works", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("to do", board.count { it.status == "TODO" || it.status == "BLOCKED" }.toString(), Info, Modifier.weight(1f).clickable { boardDetail = "TODO" })
                Tile("doing", board.count { it.status == "DOING" }.toString(), Warn, Modifier.weight(1f).clickable { boardDetail = "DOING" })
                Tile("done", board.count { it.status == "DONE" }.toString(), Good, Modifier.weight(1f).clickable { boardDetail = "DONE" })
                Tile("failed", board.count { it.status == "FAILED" }.toString(), Bad, Modifier.weight(1f).clickable { boardDetail = "FAILED" })
            }
        }
        if (agents.any { it.enabled }) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(agents.filter { it.enabled }, key = { it.id }) { a ->
                    val busy = a.name.lowercase() in doing || a.id.lowercase() in doing
                    Column(Modifier.width(84.dp).clickable { agentDetail = a }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Box(Modifier.size(60.dp).clip(CircleShape).background(roleColor(a.name)), contentAlignment = Alignment.Center) {
                                Text(a.name.split(' ').take(2).joinToString("") { it.take(1).uppercase() }, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(if (busy) Good else Color.Gray))
                        }
                        Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Done", "Failed", "Needs you")) { f ->
                    androidx.compose.material3.FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                }
            }
        }
        val shown = recent.filter {
            when (filter) {
                "Done" -> it.state == "SUCCEEDED"
                "Failed" -> it.state in setOf("FAILED", "INTERRUPTED", "MISSED", "LIMIT_REACHED")
                "Needs you" -> it.state == "NEEDS_HUMAN"
                else -> true
            }
        }.take(15)
        items(shown, key = { "r${it.id}" }) { r ->
            val color = when (r.state) { "SUCCEEDED" -> Good; "RUNNING", "STARTING" -> Info; "NEEDS_HUMAN" -> Warn; "QUEUED" -> Color.Gray; else -> Bad }
            Row(Modifier.fillMaxWidth().clickable { runDetail = r }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f)) {
                    Text(r.taskName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(time.format(Date(r.createdAtMs)), plainReason(r.state, r.reason, r.error)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(runStateLabel(r.state), style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmPause) AlertDialog(
        onDismissRequest = { confirmPause = false },
        title = { Text("Pause every team?") },
        text = { Text("All teams switch off. Switch them back on in Teams.") },
        confirmButton = { TextButton(onClick = { confirmPause = false; scope.launch { WorkspaceManager.defs(context).filter { d -> workspaces.any { it.id == d.id && it.enabled } }.forEach { runCatching { WorkspaceManager.setEnabled(context, it, false) } }; toast("Paused") } }) { Text("Pause") } },
        dismissButton = { TextButton(onClick = { confirmPause = false }) { Text("Cancel") } },
    )
    boardDetail?.let { st ->
        val list = board.filter { if (st == "TODO") it.status == "TODO" || it.status == "BLOCKED" else it.status == st }
        Detail("${st.lowercase().replaceFirstChar { it.uppercase() }} (${list.size})", { boardDetail = null }) {
            if (list.isEmpty()) Text("Nothing here.")
            list.take(30).forEach { b -> Column { Text(b.title, fontWeight = FontWeight.SemiBold); Text(listOf(b.ownerAgent, b.result.take(160)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) } }
        }
    }
    agentDetail?.let { a ->
        val mine = board.filter { it.ownerAgent.equals(a.name, true) || it.ownerAgent.equals(a.id, true) }
        Detail(a.name, { agentDetail = null }, actions = {
            TextButton(onClick = {
                val t = task.trim(); if (t.isEmpty()) return@TextButton
                scope.launch {
                    findService<TeamService>()?.run("board", mapOf("op" to "add", "title" to t.take(80), "detail" to t, "owner" to a.name, "by" to "owner", "workspace" to a.workspaceId))
                    runCatching { graph.repo.enqueueManual(com.tricreta.zafiro.autonomy.workspace.WorkspaceInstaller.taskId(a.workspaceId, "heartbeat")); graph.scheduler.reconcile("run_now") }
                    task = ""; agentDetail = null; toast("Sent to ${a.name}")
                }
            }) { Text("Send") }
        }) {
            OutlinedTextField(value = task, onValueChange = { task = it }, placeholder = { Text("Tell ${a.name} what to do") }, modifier = Modifier.fillMaxWidth())
            mine.take(8).forEach { b -> Text("• ${b.title} (${b.status.lowercase()})", style = MaterialTheme.typography.bodySmall) }
        }
    }
    runDetail?.let { r ->
        Detail(r.taskName, { runDetail = null }, actions = {
            TextButton(onClick = { scope.launch { runCatching { graph.repo.enqueueManual(r.taskId); graph.scheduler.reconcile("run_now") }; runDetail = null; toast("Started again") } }) { Text("Run again") }
        }) {
            Text("${runStateLabel(r.state)} · ${r.steps} steps · ${time.format(Date(r.createdAtMs))}", style = MaterialTheme.typography.labelLarge)
            val why = plainReason(r.state, r.reason, r.error)
            if (why.isNotBlank()) Text(why, color = Bad)
            if (!r.summary.isNullOrBlank()) Text(r.summary!!.take(1500))
        }
    }
}

/**
 * Shows a pop-up on ANY page whenever the team needs a decision, so approvals are never hidden behind a notification.
 * Hosted once at the top of the app.
 */
@Composable
fun PendingApprovalHost(onOpenDashboard: () -> Unit) {
    val graph = runCatching { AutonomyGraph.require() }.getOrNull() ?: return
    val context = LocalContext.current
    val pending by graph.repo.teamDao().observePendingActions().collectAsState(initial = emptyList())
    val names = remember { WorkspaceManager.defs(context).associate { it.id to it.name } }
    var dismissed by remember { mutableStateOf(setOf<Long>()) }
    val first = pending.firstOrNull { it.id !in dismissed } ?: return
    AlertDialog(
        onDismissRequest = { dismissed = dismissed + first.id },
        title = { Text("Approval needed (${pending.size})") },
        text = { ApprovalCard(first, names[first.workspaceId] ?: first.platform) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } },
        confirmButton = { TextButton(onClick = { dismissed = dismissed + first.id; onOpenDashboard() }) { Text("Open dashboard") } },
        dismissButton = { TextButton(onClick = { dismissed = dismissed + first.id }) { Text("Later") } },
    )
}
