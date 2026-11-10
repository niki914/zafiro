package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.nav.TaskRunPage
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.data.RunEntity
import com.tricreta.zafiro.autonomy.data.RunEventEntity

private data class RunDetail(val run: RunEntity, val events: List<RunEventEntity>)

/** Read-only report and log of a single run: what the agent did, how it ended, and why. */
@Composable
fun TaskRunContent(page: TaskRunPage) {
    val detail by produceState<RunDetail?>(initialValue = null, page.runId) {
        val repo = AutonomyGraph.require().repo
        value = repo.run(page.runId)?.let { RunDetail(it, repo.events(page.runId)) }
    }
    val d = detail
    val reportTitle = stringResource(R.string.task_run_report)
    val problemTitle = stringResource(R.string.task_run_problem)
    val logTitle = stringResource(R.string.task_run_log)

    val sections = buildList {
        if (d != null) {
            val r = d.run
            add(
                SettingsSectionSpec(
                    title = r.taskName,
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = listOf(
                        SettingsRowSpec.Value(title = "Result", currentState = runStateLabel(r.state)),
                        SettingsRowSpec.Value(title = "Scheduled for", currentState = formatWhen(r.scheduledForMs)),
                        SettingsRowSpec.Value(title = "Started", currentState = formatWhen(r.startedAtMs)),
                        SettingsRowSpec.Value(title = "Finished", currentState = formatWhen(r.finishedAtMs)),
                        SettingsRowSpec.Value(title = "Tool calls", currentState = r.steps.toString()),
                    ),
                ),
            )
            r.summary?.takeIf { it.isNotBlank() }?.let {
                add(SettingsSectionSpec(title = reportTitle, rows = listOf(SettingsRowSpec.Message(title = it))))
            }
            r.error?.takeIf { it.isNotBlank() }?.let {
                add(SettingsSectionSpec(title = problemTitle, rows = listOf(SettingsRowSpec.Message(title = it))))
            }
            if (d.events.isNotEmpty()) {
                add(
                    SettingsSectionSpec(
                        title = logTitle,
                        layout = SettingsSectionLayout.GroupedCard,
                        rows = d.events.map { e ->
                            SettingsRowSpec.Value(title = e.text, summary = "${formatWhen(e.tsMs)} · ${e.kind}")
                        },
                    ),
                )
            }
        }
    }
    SettingsSpecPageContent(spec = SettingsPageSpec(sections = sections)) { }
}
