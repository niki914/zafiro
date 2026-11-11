package com.niki914.zafiro.app.ui.content

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.TasksEffect
import com.niki914.zafiro.app.ui.model.TasksIntent
import com.niki914.zafiro.app.ui.model.TasksUiState
import com.niki914.zafiro.app.ui.model.TasksViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import com.tricreta.zafiro.autonomy.data.describeSchedule
import com.tricreta.zafiro.autonomy.reliability.OemAdvice
import com.tricreta.zafiro.autonomy.reliability.OemGuide
import com.tricreta.zafiro.autonomy.reliability.Reliability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ID_TASK = "task."
private const val ID_RUN = "run."
private const val ACT_EXACT = "act.exact"
private const val ACT_BATTERY = "act.battery"
private const val ACT_PHONE_SETUP = "act.phone_setup"
private const val ACT_SELF_TEST = "act.self_test"

private val whenFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

internal fun formatWhen(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(whenFormat) } ?: "—"

/** The scheduled-tasks page: reliability facts, your tasks, and recent runs, built from Zafiro's own settings components. */
@Composable
fun TasksPageContent(
    onOpenEditor: (taskId: String?) -> Unit,
    onOpenRun: (runId: Long) -> Unit,
) {
    val viewModel = pageViewModel<TasksViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    val latestOpenEditor by rememberUpdatedState(onOpenEditor)
    val latestOpenRun by rememberUpdatedState(onOpenRun)

    val chrome = remember {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = { latestOpenEditor(null) },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(chrome)

    LaunchedEffect(viewModel) { viewModel.sendIntent(TasksIntent.Start) }

    // Results and approvals reach the user as notifications: ask through PermissionManager like every other permission.
    LaunchedEffect(Unit) {
        val pm = requireService<PermissionManager>()
        if (pm.status(Permission.NOTIFICATION) != PermissionState.GRANTED) {
            runCatching { pm.request(Permission.NOTIFICATION) }
        }
    }

    // Coming back from a system settings screen: re-read what the user may just have changed.
    DisposableEffect(context) {
        val owner = context.findLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.sendIntent(TasksIntent.Refresh)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is TasksEffect.Message -> Toast.makeText(context, effect.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    var showPhoneSetup by rememberSaveable { mutableStateOf(false) }
    val advice = remember { OemGuide.adviceForThisPhone() }
    val spec = tasksSpec(uiState, advice)

    SettingsSpecPageContent(spec = spec) { action ->
        val id = when (action) {
            is SettingsRowAction.Navigate -> action.id
            is SettingsRowAction.Click -> action.id
            else -> null
        }
        when {
            action is SettingsRowAction.ToggleChanged && action.id.startsWith(ID_TASK) ->
                viewModel.sendIntent(TasksIntent.SetEnabled(action.id.removePrefix(ID_TASK), action.checked))

            id == null -> Unit
            id.startsWith(ID_TASK) -> latestOpenEditor(id.removePrefix(ID_TASK))
            id.startsWith(ID_RUN) -> id.removePrefix(ID_RUN).toLongOrNull()?.let { latestOpenRun(it) }
            id == ACT_EXACT -> Reliability.exactAlarmSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
            id == ACT_BATTERY -> runCatching { context.startActivity(Reliability.batteryExemptionIntent(context)) }
                .onFailure { runCatching { context.startActivity(Reliability.batteryListIntent()) } }

            id == ACT_PHONE_SETUP -> showPhoneSetup = true
            id == ACT_SELF_TEST -> viewModel.sendIntent(TasksIntent.SelfTest)
        }
    }

    ZafiroConfirmationDialog(
        visible = showPhoneSetup,
        onDismissRequest = { showPhoneSetup = false },
        title = advice.vendorLabel,
        text = advice.steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n\n") +
            "\n\n" + stringResource(R.string.tasks_rel_setup_note),
        negativeButtonText = stringResource(R.string.tasks_rel_setup_close),
        positiveButtonText = stringResource(R.string.tasks_rel_setup_open),
        onNegativeClick = { showPhoneSetup = false },
        onPositiveClick = {
            OemGuide.openSettings(context, advice)
            showPhoneSetup = false
        },
    )
}

@Composable
private fun tasksSpec(ui: TasksUiState, advice: OemAdvice): SettingsPageSpec {
    val allowed = stringResource(R.string.tasks_rel_allowed)
    val notAllowed = stringResource(R.string.tasks_rel_not_allowed)
    val exempt = stringResource(R.string.tasks_rel_exempt)
    val restricted = stringResource(R.string.tasks_rel_restricted)
    val none = stringResource(R.string.tasks_rel_none)
    val paused = stringResource(R.string.tasks_state_disabled)

    val reliabilityRows = buildList<SettingsRowSpec> {
        val r = ui.reliability
        if (r != null) {
            add(
                SettingsRowSpec.Value(
                    title = stringResource(R.string.tasks_rel_alarms),
                    currentState = if (r.exactAlarmAllowed) allowed else notAllowed,
                ),
            )
            if (!r.exactAlarmAllowed && Reliability.exactAlarmSettingsIntent(LocalContext.current) != null) {
                add(
                    SettingsRowSpec.Action(
                        id = ACT_EXACT,
                        title = stringResource(R.string.tasks_rel_allow_alarms),
                        summary = stringResource(R.string.tasks_rel_allow_alarms_summary),
                    ),
                )
            }
            add(
                SettingsRowSpec.Value(
                    title = stringResource(R.string.tasks_rel_battery),
                    currentState = if (r.ignoringBatteryOptimizations) exempt else restricted,
                ),
            )
            if (!r.ignoringBatteryOptimizations) {
                add(
                    SettingsRowSpec.Action(
                        id = ACT_BATTERY,
                        title = stringResource(R.string.tasks_rel_exempt_action),
                        summary = stringResource(R.string.tasks_rel_exempt_action_summary),
                    ),
                )
            }
            add(
                SettingsRowSpec.Value(
                    title = stringResource(R.string.tasks_rel_next_wake),
                    currentState = r.nextWake?.let { formatWhen(it.toEpochMilli()) } ?: none,
                ),
            )
        }
        add(
            SettingsRowSpec.Navigation(
                id = ACT_PHONE_SETUP,
                title = stringResource(R.string.tasks_rel_phone_setup),
                summary = advice.vendorLabel,
            ),
        )
        add(
            SettingsRowSpec.Action(
                id = ACT_SELF_TEST,
                title = stringResource(R.string.tasks_rel_self_test),
                summary = stringResource(R.string.tasks_rel_self_test_summary),
            ),
        )
    }

    val taskSection = if (ui.tasks.isEmpty()) {
        SettingsSectionSpec(
            title = stringResource(R.string.tasks_section_tasks),
            layout = SettingsSectionLayout.GroupedCard,
            rows = listOf(SettingsRowSpec.Message(title = stringResource(R.string.tasks_empty))),
        )
    } else {
        SettingsSectionSpec(
            title = stringResource(R.string.tasks_section_tasks),
            layout = SettingsSectionLayout.CardList,
            rows = ui.tasks.map { t ->
                SettingsRowSpec.ToggleNavigation(
                    id = ID_TASK + t.id,
                    title = t.name,
                    summary = t.describeSchedule() + if (t.enabled) "" else " · $paused",
                    checked = t.enabled,
                )
            },
        )
    }

    val runSection = SettingsSectionSpec(
        title = stringResource(R.string.tasks_section_runs),
        layout = if (ui.runs.isEmpty()) SettingsSectionLayout.GroupedCard else SettingsSectionLayout.CardList,
        rows = if (ui.runs.isEmpty()) {
            listOf(SettingsRowSpec.Message(title = stringResource(R.string.tasks_runs_empty)))
        } else {
            ui.runs.take(30).map { r ->
                SettingsRowSpec.Navigation(
                    id = ID_RUN + r.id,
                    title = r.taskName.ifBlank { "Task" },
                    summary = formatWhen(r.scheduledForMs) + ((r.summary ?: r.error)?.let { " · " + it.take(80).replace('\n', ' ') } ?: ""),
                    currentState = runStateLabel(r.state),
                )
            }
        },
    )

    return SettingsPageSpec(
        description = stringResource(R.string.tasks_page_description),
        sections = listOf(
            taskSection,
            SettingsSectionSpec(
                title = stringResource(R.string.tasks_section_reliability),
                layout = SettingsSectionLayout.GroupedCard,
                rows = reliabilityRows,
            ),
            runSection,
        ),
    )
}

internal fun runStateLabel(state: String): String = when (state) {
    "SUCCEEDED" -> "Done"
    "FAILED" -> "Failed"
    "MISSED" -> "Missed"
    "RUNNING" -> "Running"
    "STARTING" -> "Starting"
    "QUEUED" -> "Queued"
    "PAUSED" -> "Waiting"
    "LIMIT_REACHED" -> "Hit its limit"
    "NEEDS_HUMAN" -> "Needs you"
    "INTERRUPTED" -> "Interrupted"
    "CANCELLED" -> "Cancelled"
    else -> state
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
