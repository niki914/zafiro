package com.niki914.zafiro.app.ui.content

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.TaskEditorEffect
import com.niki914.zafiro.app.ui.model.TaskEditorIntent
import com.niki914.zafiro.app.ui.model.TaskEditorUiState
import com.niki914.zafiro.app.ui.model.TaskEditorViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.model.unsavedFieldLabelRes
import com.niki914.zafiro.app.ui.nav.TaskEditorPage
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem
import com.tricreta.zafiro.autonomy.form.ScheduleKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TaskEditorField { Name, Prompt, Interval, Steps, Minutes }

/**
 * Create / edit a scheduled task. Date and time come from the system pickers (they follow the phone's 12h/24h setting), so a
 * time can never be mis-typed or misread; the time zone is the phone's, shown but not typed.
 */
@Composable
fun TaskEditorContent(
    page: TaskEditorPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<TaskEditorViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    var requestedFocusField by rememberSaveable { mutableStateOf<TaskEditorField?>(null) }

    EditableSettingsDetailChrome(
        isCreating = page.taskId == null,
        hasUnsavedChanges = { uiState.hasUnsavedChanges },
        unsavedFieldLabelRes = { uiState.unsavedFieldLabelRes() },
        onDelete = { viewModel.sendIntent(TaskEditorIntent.RequestDelete) },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = { uiState.deleteConfirmation != null },
        onDismissDeleteConfirmation = { viewModel.sendIntent(TaskEditorIntent.DismissDeleteConfirmation) },
    ) {
        TaskEditorBody(
            uiState = uiState,
            isEditing = page.taskId != null,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = { requestedFocusField = null },
            onIntent = viewModel::sendIntent,
            context = context,
        )

        ZafiroConfirmationDialog(
            visible = uiState.deleteConfirmation != null,
            onDismissRequest = { viewModel.sendIntent(TaskEditorIntent.DismissDeleteConfirmation) },
            title = stringResource(R.string.task_delete_title),
            text = stringResource(R.string.task_delete_text, uiState.deleteConfirmation.orEmpty()),
            negativeButtonText = stringResource(R.string.delete_dialog_cancel),
            positiveButtonText = stringResource(R.string.delete_dialog_confirm),
            onNegativeClick = { viewModel.sendIntent(TaskEditorIntent.DismissDeleteConfirmation) },
            onPositiveClick = { viewModel.sendIntent(TaskEditorIntent.ConfirmDelete) },
        )
    }

    LaunchedEffect(page.routeKey) { viewModel.sendIntent(TaskEditorIntent.Load(page.taskId)) }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                TaskEditorEffect.ExitDetail -> onBack()
                is TaskEditorEffect.Message -> Toast.makeText(context, effect.text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun TaskEditorBody(
    uiState: TaskEditorUiState,
    isEditing: Boolean,
    requestedFocusField: TaskEditorField?,
    onRequestedFocusHandled: () -> Unit,
    onIntent: (TaskEditorIntent) -> Unit,
    context: Context,
) {
    val form = uiState.form ?: return
    val draft = form.draft
    val busy = uiState.isSaving
    var showKindDialog by rememberSaveable { mutableStateOf(false) }

    val kindLabels = mapOf(
        ScheduleKind.MANUAL to stringResource(R.string.task_kind_manual),
        ScheduleKind.ONCE to stringResource(R.string.task_kind_once),
        ScheduleKind.DAILY to stringResource(R.string.task_kind_daily),
        ScheduleKind.WEEKDAYS to stringResource(R.string.task_kind_weekdays),
        ScheduleKind.INTERVAL to stringResource(R.string.task_kind_interval),
    )
    val is24h = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val zoneText = stringResource(R.string.task_field_timezone_value, zoneLabel(zone))

    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.task_editor_save),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = { onIntent(TaskEditorIntent.Save) },
        description = stringResource(R.string.task_editor_description),
        inlineErrorText = uiState.error?.message,
        actionEnabled = !busy,
    ) { fieldController ->
        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TaskEditorField.Name,
                controller = fieldController,
                title = stringResource(R.string.task_field_name),
                value = draft.name,
                onValueChange = { onIntent(TaskEditorIntent.NameChanged(it)) },
                placeholder = stringResource(R.string.task_field_name_hint),
                enabled = !busy,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.task_field_enabled),
                checked = form.enabled,
                enabled = !busy,
                onCheckedChange = { onIntent(TaskEditorIntent.EnabledChanged(it)) },
            )
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TaskEditorField.Prompt,
                controller = fieldController,
                title = stringResource(R.string.task_field_prompt),
                value = draft.prompt,
                onValueChange = { onIntent(TaskEditorIntent.PromptChanged(it)) },
                placeholder = stringResource(R.string.task_field_prompt_hint),
                enabled = !busy,
                minLines = 4,
                maxLines = 10,
            )
        }

        SettingsGroupCard {
            SettingNavigationItem(
                title = stringResource(R.string.task_field_schedule),
                currentState = kindLabels.getValue(draft.kind),
                enabled = !busy,
                onClick = { showKindDialog = true },
            )
            if (draft.kind == ScheduleKind.ONCE) {
                SettingsItemDivider()
                SettingNavigationItem(
                    title = stringResource(R.string.task_field_date),
                    currentState = formatDate(draft.date),
                    enabled = !busy,
                    onClick = { pickDate(context, draft.date) { onIntent(TaskEditorIntent.DatePicked(it)) } },
                )
            }
            if (draft.kind == ScheduleKind.ONCE || draft.kind == ScheduleKind.DAILY || draft.kind == ScheduleKind.WEEKDAYS) {
                SettingsItemDivider()
                SettingNavigationItem(
                    title = stringResource(R.string.task_field_time),
                    summary = zoneText,
                    currentState = formatTime(draft.time, is24h),
                    enabled = !busy,
                    onClick = { pickTime(context, draft.time, is24h) { onIntent(TaskEditorIntent.TimePicked(it)) } },
                )
            }
            if (draft.kind == ScheduleKind.INTERVAL) {
                SettingsItemDivider()
                SettingControlledExpandableTextItem(
                    field = TaskEditorField.Interval,
                    controller = fieldController,
                    title = stringResource(R.string.task_field_interval),
                    value = draft.everyMinutes,
                    onValueChange = { onIntent(TaskEditorIntent.EveryChanged(it)) },
                    placeholder = stringResource(R.string.task_field_interval_hint),
                    enabled = !busy,
                )
            }
        }

        SettingsGroupCard {
            SettingControlledExpandableTextItem(
                field = TaskEditorField.Steps,
                controller = fieldController,
                title = stringResource(R.string.task_field_max_steps),
                value = draft.maxSteps,
                onValueChange = { onIntent(TaskEditorIntent.StepsChanged(it)) },
                placeholder = "30",
                enabled = !busy,
            )
            SettingsItemDivider()
            SettingControlledExpandableTextItem(
                field = TaskEditorField.Minutes,
                controller = fieldController,
                title = stringResource(R.string.task_field_max_minutes),
                value = draft.maxMinutes,
                onValueChange = { onIntent(TaskEditorIntent.MinutesChanged(it)) },
                placeholder = "10",
                enabled = !busy,
            )
        }

        if (isEditing) {
            SettingsGroupCard {
                SettingNavigationItem(
                    title = stringResource(R.string.tasks_run_now),
                    enabled = !busy,
                    onClick = { onIntent(TaskEditorIntent.RunNow) },
                )
            }
        }
    }

    ZafiroSingleChoiceDialog(
        visible = showKindDialog,
        onDismissRequest = { showKindDialog = false },
        title = stringResource(R.string.task_kind_dialog_title),
        options = ScheduleKind.entries.toList(),
        selectedId = draft.kind.name,
        optionId = { it.name },
        optionLabel = { kindLabels.getValue(it) },
        onSelect = {
            showKindDialog = false
            onIntent(TaskEditorIntent.KindChanged(it))
        },
    )
}

private fun zoneLabel(zone: ZoneId): String {
    val offset = ZonedDateTime.now(zone).offset.id.let { if (it == "Z") "+00:00" else it }
    return "${zone.id}, UTC$offset"
}

private fun formatDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))

private fun formatTime(t: LocalTime, is24h: Boolean): String =
    t.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.ENGLISH))

private fun pickDate(context: Context, current: LocalDate, onPicked: (LocalDate) -> Unit) {
    val dialog = DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
        current.year, current.monthValue - 1, current.dayOfMonth,
    )
    dialog.datePicker.minDate = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dialog.show()
}

private fun pickTime(context: Context, current: LocalTime, is24h: Boolean, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
        current.hour, current.minute, is24h,
    ).show()
}
