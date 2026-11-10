package com.tricreta.zafiro.autonomy.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.core.RunState
import com.tricreta.zafiro.autonomy.data.RunEntity
import com.tricreta.zafiro.autonomy.data.RunEventEntity
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.describeSchedule
import com.tricreta.zafiro.autonomy.reliability.OemGuide
import com.tricreta.zafiro.autonomy.schedule.AlarmProjection
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Task management screen (Phase 2a: functional, deliberately plain). A separate launcher entry so no upstream UI file changes;
 * it is folded into the main settings navigation in Phase 2b.
 */
class AutonomyActivity : ComponentActivity() {

    private val resumeTick = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val graph = AutonomyGraph.get(this)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AutonomyScreen(graph, resumeTick.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        val graph = AutonomyGraph.get(this)
        graph.scope.launch { graph.scheduler.reconcile("ui_resume") }
    }
}

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

private fun fmt(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) } ?: "—"

@Composable
private fun AutonomyScreen(graph: AutonomyGraph, resumeTick: Int) {
    val scope = rememberCoroutineScope()
    val tasks by graph.repo.observeTasks().collectAsState(initial = emptyList())
    val runs by graph.repo.observeRuns().collectAsState(initial = emptyList())
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var openRun by remember { mutableStateOf<RunEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Scheduled tasks", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Tasks run at their time even when the app is closed. Android may delay or stop background apps; " +
                    "anything missed is recorded below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { StatusCard(graph, resumeTick) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = null; showEditor = true }) { Text("New task") }
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.repo.saveTask(TaskForm.selfTest())
                        graph.scheduler.reconcile("self_test")
                    }
                }) { Text("Self-test in 1 min") }
            }
        }
        if (tasks.isEmpty()) {
            item { Text("No tasks yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onToggle = { enabled ->
                    scope.launch { graph.repo.setEnabled(task.id, enabled); graph.scheduler.reconcile("toggle") }
                },
                onRunNow = {
                    scope.launch { graph.repo.enqueueManual(task.id); graph.scheduler.reconcile("run_now") }
                },
                onEdit = { editing = task; showEditor = true },
                onDelete = {
                    scope.launch { graph.repo.deleteTask(task.id); graph.scheduler.reconcile("delete") }
                },
            )
        }
        item {
            HorizontalDivider()
            Text("History", style = MaterialTheme.typography.titleMedium)
        }
        if (runs.isEmpty()) item { Text("No runs yet.", style = MaterialTheme.typography.bodyMedium) }
        items(runs, key = { it.id }) { run -> RunRow(run) { openRun = run } }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (showEditor) {
        TaskEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { task ->
                showEditor = false
                scope.launch { graph.repo.saveTask(task); graph.scheduler.reconcile("save") }
            },
        )
    }
    openRun?.let { run ->
        RunDetailDialog(graph, run) { openRun = null }
    }
}

@Composable
private fun StatusCard(graph: AutonomyGraph, resumeTick: Int) {
    val context = LocalContext.current
    // resumeTick forces a re-read after returning from a system settings screen.
    val exact = remember(resumeTick) { graph.alarm.canScheduleExact() }
    val ignoringBattery = remember(resumeTick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val snap = remember(resumeTick) { graph.scheduler.lastSnapshot() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Reliability", style = MaterialTheme.typography.titleMedium)
            Text("Next wake-up: ${fmt(snap?.nextWake?.toEpochMilli())}")
            Text(
                when (snap?.armed) {
                    AlarmProjection.Armed.EXACT -> "Alarm: exact ✓"
                    AlarmProjection.Armed.INEXACT -> "Alarm: NOT exact — may be late (allow Alarms & reminders)"
                    AlarmProjection.Armed.CANCELLED, null -> "Alarm: none scheduled"
                },
            )
            Text(if (exact) "Alarms & reminders: allowed ✓" else "Alarms & reminders: NOT allowed ✗")
            Text(if (ignoringBattery) "Battery optimisation: exempt ✓" else "Battery optimisation: ON — Android may stop background work ✗")
            Text(
                "Last check: ${fmt(snap?.reconciledAtMs)} (${snap?.reason ?: "never"})",
                style = MaterialTheme.typography.bodySmall,
            )
            val advice = remember { OemGuide.adviceForThisPhone() }
            var showAdvice by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdvice = !showAdvice }) {
                Text(if (showAdvice) "Hide ${advice.vendorLabel} setup" else "Show ${advice.vendorLabel} setup")
            }
            if (showAdvice) {
                Text(
                    "These steps are general guidance for this brand and may differ on your model. " +
                        "The \"Self-test in 1 min\" button is the real check.",
                    style = MaterialTheme.typography.bodySmall,
                )
                advice.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { OemGuide.openSettings(context, advice) }) { Text("Open phone settings") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!exact && Build.VERSION.SDK_INT >= 31) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Allow alarms") }
                }
                if (!ignoringBattery) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Exempt from battery limits") }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Text(task.describeSchedule(), style = MaterialTheme.typography.bodySmall)
            Text(task.prompt.take(160), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRunNow) { Text("Run now") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text("${run.taskName} — ${run.state}${if (run.manual) " (manual)" else ""}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${fmt(run.scheduledForMs)} · steps ${run.steps}" + (run.summary ?: run.error)?.let { " · ${it.take(90)}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RunDetailDialog(graph: AutonomyGraph, run: RunEntity, onDismiss: () -> Unit) {
    var events by remember(run.id) { mutableStateOf<List<RunEventEntity>>(emptyList()) }
    LaunchedEffect(run.id) { events = graph.repo.events(run.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("${run.taskName}: ${run.state}") },
        text = {
            LazyColumn {
                item {
                    Text("Scheduled ${fmt(run.scheduledForMs)}; started ${fmt(run.startedAtMs)}; finished ${fmt(run.finishedAtMs)}")
                    run.summary?.let { Text("\nReport:\n$it") }
                    run.error?.let { Text("\nProblem:\n$it") }
                    if (run.state == RunState.MISSED.name) Text("\nOccurrences covered: ${run.missedCount}")
                    Text("\nLog:")
                }
                items(events) { e -> Text("${fmt(e.tsMs)} [${e.kind}] ${e.text}", style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

@Composable
private fun TaskEditorDialog(initial: TaskEntity?, onDismiss: () -> Unit, onSave: (TaskEntity) -> Unit) {
    var form by remember(initial?.id) { mutableStateOf(initial?.let { TaskForm.from(it) } ?: TaskForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New task" else "Edit task") },
        confirmButton = {
            TextButton(onClick = {
                val result = form.toEntity(initial?.id ?: UUID.randomUUID().toString(), initial?.createdAtMs)
                result.onSuccess(onSave).onFailure { error = it.message }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                }
                item {
                    OutlinedTextField(
                        form.prompt, { form = form.copy(prompt = it) },
                        label = { Text("What should Zafiro do?") }, minLines = 3,
                    )
                }
                item {
                    Text("Schedule", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ScheduleKind.entries.forEach { k ->
                            if (k == form.kind) Button(onClick = {}) { Text(k.label) }
                            else OutlinedButton(onClick = { form = form.copy(kind = k) }) { Text(k.label) }
                        }
                    }
                }
                if (form.kind == ScheduleKind.ONCE) item {
                    OutlinedTextField(form.date, { form = form.copy(date = it) }, label = { Text("Date (yyyy-MM-dd)") }, singleLine = true)
                }
                if (form.kind in listOf(ScheduleKind.ONCE, ScheduleKind.DAILY, ScheduleKind.WEEKDAYS)) item {
                    OutlinedTextField(form.time, { form = form.copy(time = it) }, label = { Text("Time (HH:mm, 24h)") }, singleLine = true)
                }
                if (form.kind == ScheduleKind.INTERVAL) item {
                    OutlinedTextField(form.everyMinutes, { form = form.copy(everyMinutes = it) }, label = { Text("Every N minutes (min 1)") }, singleLine = true)
                }
                if (form.kind != ScheduleKind.MANUAL) item {
                    OutlinedTextField(form.zone, { form = form.copy(zone = it) }, label = { Text("Time zone") }, singleLine = true)
                }
                item {
                    OutlinedTextField(form.maxSteps, { form = form.copy(maxSteps = it) }, label = { Text("Max tool calls per run") }, singleLine = true)
                }
                item {
                    OutlinedTextField(form.maxMinutes, { form = form.copy(maxMinutes = it) }, label = { Text("Max minutes per run") }, singleLine = true)
                }
                error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            }
        },
    )
}
