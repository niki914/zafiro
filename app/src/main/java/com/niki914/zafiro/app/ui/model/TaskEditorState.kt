package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.app.ui.settings.form.buildFormSpec
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.form.DraftField
import com.tricreta.zafiro.autonomy.form.DraftResult
import com.tricreta.zafiro.autonomy.form.ScheduleKind
import com.tricreta.zafiro.autonomy.form.TaskDraft
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Everything the editor edits; compared against [TaskEditorUiState.initial] for the unsaved-changes guard. */
data class TaskEditorForm(
    val draft: TaskDraft,
    val enabled: Boolean = true,
)

val TaskEditorFormSpec = buildFormSpec<TaskEditorForm, String> {
    field("name", R.string.task_field_name) { it.draft.name }
    field("prompt", R.string.task_field_prompt) { it.draft.prompt }
    field("enabled", R.string.task_field_enabled) { it.enabled }
    field("kind", R.string.task_field_schedule) { it.draft.kind }
    field("date", R.string.task_field_date) { it.draft.date }
    field("time", R.string.task_field_time) { it.draft.time }
    field("interval", R.string.task_field_interval) { it.draft.everyMinutes }
    field("steps", R.string.task_field_max_steps) { it.draft.maxSteps }
    field("minutes", R.string.task_field_max_minutes) { it.draft.maxMinutes }
    field("days", R.string.task_field_days) { it.draft.days }
    field("dayOfMonth", R.string.task_field_day_of_month) { it.draft.dayOfMonth }
    field("tokens", R.string.task_field_max_tokens) { it.draft.maxTokensK }
    field("recoveries", R.string.task_field_recoveries) { it.draft.recoveries }
    field("criteria", R.string.task_field_criteria) { it.draft.successCriteria }
    field("model", R.string.task_field_model) { it.draft.configId }
}

data class TaskEditorUiState(
    val taskId: String? = null,
    val form: TaskEditorForm? = null,
    val initial: TaskEditorForm? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: TaskEditorError? = null,
    val deleteConfirmation: String? = null,
    val configs: List<SavedConfigSummary> = emptyList(),
)

data class TaskEditorError(val field: DraftField?, val message: String)

val TaskEditorUiState.hasUnsavedChanges: Boolean
    get() = form != null && TaskEditorFormSpec.hasChanges(initial, form)

fun TaskEditorUiState.unsavedFieldLabelRes(): List<Int> =
    if (form == null) emptyList() else TaskEditorFormSpec.dirtyFieldLabelRes(initial, form)

sealed interface TaskEditorIntent {
    data class Load(val taskId: String?) : TaskEditorIntent
    data class NameChanged(val value: String) : TaskEditorIntent
    data class PromptChanged(val value: String) : TaskEditorIntent
    data class EnabledChanged(val value: Boolean) : TaskEditorIntent
    data class KindChanged(val value: ScheduleKind) : TaskEditorIntent
    data class DatePicked(val value: LocalDate) : TaskEditorIntent
    data class TimePicked(val value: LocalTime) : TaskEditorIntent
    data class EveryChanged(val value: String) : TaskEditorIntent
    data class StepsChanged(val value: String) : TaskEditorIntent
    data class MinutesChanged(val value: String) : TaskEditorIntent
    data class DayToggled(val day: DayOfWeek, val selected: Boolean) : TaskEditorIntent
    data class DayOfMonthChanged(val value: String) : TaskEditorIntent
    data class TokensChanged(val value: String) : TaskEditorIntent
    data class RecoveriesChanged(val value: String) : TaskEditorIntent
    data class CriteriaChanged(val value: String) : TaskEditorIntent
    data class ConfigPicked(val configId: String) : TaskEditorIntent
    data object Save : TaskEditorIntent
    data object RequestDelete : TaskEditorIntent
    data object DismissDeleteConfirmation : TaskEditorIntent
    data object ConfirmDelete : TaskEditorIntent
    data object RunNow : TaskEditorIntent
}

sealed interface TaskEditorEffect {
    data object ExitDetail : TaskEditorEffect
    data class Message(val text: String) : TaskEditorEffect
}

class TaskEditorViewModel : ComposeMVIViewModel<TaskEditorIntent, TaskEditorUiState, TaskEditorEffect>() {

    private val graph: AutonomyGraph get() = AutonomyGraph.require()
    private var existing: TaskEntity? = null

    override fun initUiState(): TaskEditorUiState = TaskEditorUiState()

    /** The phone's zone at this moment, read fresh every time (it can change while the editor is open). */
    private fun deviceZone(): ZoneId = ZoneId.systemDefault()

    override suspend fun handleIntent(intent: TaskEditorIntent) {
        when (intent) {
            is TaskEditorIntent.Load -> load(intent.taskId)
            is TaskEditorIntent.NameChanged -> edit { copy(name = intent.value) }
            is TaskEditorIntent.PromptChanged -> edit { copy(prompt = intent.value) }
            is TaskEditorIntent.EnabledChanged -> updateState {
                copy(form = form?.copy(enabled = intent.value), error = null)
            }
            is TaskEditorIntent.KindChanged -> edit { copy(kind = intent.value) }
            is TaskEditorIntent.DatePicked -> edit { copy(date = intent.value) }
            is TaskEditorIntent.TimePicked -> edit { copy(time = intent.value) }
            is TaskEditorIntent.EveryChanged -> edit { copy(everyMinutes = intent.value) }
            is TaskEditorIntent.StepsChanged -> edit { copy(maxSteps = intent.value) }
            is TaskEditorIntent.MinutesChanged -> edit { copy(maxMinutes = intent.value) }
            is TaskEditorIntent.DayToggled -> edit {
                copy(days = if (intent.selected) days + intent.day else days - intent.day)
            }
            is TaskEditorIntent.DayOfMonthChanged -> edit { copy(dayOfMonth = intent.value) }
            is TaskEditorIntent.TokensChanged -> edit { copy(maxTokensK = intent.value) }
            is TaskEditorIntent.RecoveriesChanged -> edit { copy(recoveries = intent.value) }
            is TaskEditorIntent.CriteriaChanged -> edit { copy(successCriteria = intent.value) }
            is TaskEditorIntent.ConfigPicked -> edit { copy(configId = intent.configId) }
            TaskEditorIntent.Save -> save()
            TaskEditorIntent.RequestDelete -> updateState { copy(deleteConfirmation = existing?.name.orEmpty()) }
            TaskEditorIntent.DismissDeleteConfirmation -> updateState { copy(deleteConfirmation = null) }
            TaskEditorIntent.ConfirmDelete -> delete()
            TaskEditorIntent.RunNow -> runNow()
        }
    }

    private fun edit(change: TaskDraft.() -> TaskDraft) {
        updateState { copy(form = form?.copy(draft = form.draft.change()), error = null) }
    }

    private suspend fun load(taskId: String?) {
        if (currentState.form != null && currentState.taskId == taskId) return
        val today = LocalDate.now(deviceZone())
        val task = taskId?.let { graph.repo.task(it) }
        existing = task
        val form = if (task != null) {
            TaskEditorForm(TaskDraft.from(task, today), task.enabled)
        } else {
            TaskEditorForm(TaskDraft.new(today), true)
        }
        val configs = runCatching { summariesOf(XRepo.llmConfigs.document()) }.getOrDefault(emptyList())
        updateState { copy(taskId = taskId, form = form, initial = form, isLoading = false, error = null, configs = configs) }
    }

    private suspend fun save() {
        val form = currentState.form ?: return
        if (currentState.isSaving) return
        updateState { copy(isSaving = true, error = null) }
        val now = System.currentTimeMillis()
        val id = currentState.taskId ?: UUID.randomUUID().toString()
        when (val result = form.draft.toEntity(id, existing?.createdAtMs, now, deviceZone(), form.enabled)) {
            is DraftResult.Invalid -> updateState {
                copy(isSaving = false, error = TaskEditorError(result.field, result.message))
            }
            is DraftResult.Ok -> {
                val keep = existing
                val task = if (keep == null) result.task else result.task.copy(
                    misfireGraceMs = keep.misfireGraceMs,
                    overlap = keep.overlap,
                    approvalTimeoutMs = keep.approvalTimeoutMs,
                )
                runCatching {
                    graph.repo.saveTask(task)
                    graph.scheduler.reconcile("save")
                }.onSuccess {
                    Logger.i(LOG_TAG, "saved task id=$id kind=${form.draft.kind}")
                    updateState { copy(isSaving = false) }
                    sendEffect(TaskEditorEffect.ExitDetail)
                }.onFailure { t ->
                    Logger.e(LOG_TAG, "save failed", t)
                    updateState { copy(isSaving = false, error = TaskEditorError(null, t.message ?: "Could not save the task.")) }
                }
            }
        }
    }

    private suspend fun runNow() {
        val id = currentState.taskId ?: return
        graph.repo.enqueueManual(id)
        graph.scheduler.reconcile("run_now")
        sendEffect(TaskEditorEffect.Message("Started. The result appears under Recent runs."))
    }

    private suspend fun delete() {
        val id = currentState.taskId ?: return
        updateState { copy(deleteConfirmation = null, isSaving = true) }
        runCatching {
            graph.repo.deleteTask(id)
            graph.scheduler.reconcile("delete")
        }.onSuccess {
            updateState { copy(isSaving = false, initial = form) } // nothing left to lose: skip the unsaved-changes prompt
            sendEffect(TaskEditorEffect.ExitDetail)
        }.onFailure { t ->
            updateState { copy(isSaving = false, error = TaskEditorError(null, t.message ?: "Could not delete the task.")) }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_TaskEditorViewModel"
    }
}
