package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.data.RunEntity
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.form.TaskDraft
import com.tricreta.zafiro.autonomy.reliability.Reliability
import com.tricreta.zafiro.autonomy.reliability.ReliabilityStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZoneId

data class TasksUiState(
    val tasks: List<TaskEntity> = emptyList(),
    val runs: List<RunEntity> = emptyList(),
    val reliability: ReliabilityStatus? = null,
    val isLoading: Boolean = true,
)

sealed interface TasksIntent {
    /** Begin observing tasks and runs (idempotent). */
    data object Start : TasksIntent

    /** Re-read the reliability facts (call when returning from a system settings screen). */
    data object Refresh : TasksIntent
    data class SetEnabled(val id: String, val enabled: Boolean) : TasksIntent
    data class RunNow(val id: String) : TasksIntent
    data object SelfTest : TasksIntent
}

sealed interface TasksEffect {
    data class Message(val text: String) : TasksEffect
}

class TasksViewModel : ComposeMVIViewModel<TasksIntent, TasksUiState, TasksEffect>() {

    private val graph: AutonomyGraph get() = AutonomyGraph.require()
    private var started = false

    override fun initUiState(): TasksUiState = TasksUiState()

    override suspend fun handleIntent(intent: TasksIntent) {
        when (intent) {
            TasksIntent.Start -> start()
            TasksIntent.Refresh -> refreshReliability()
            is TasksIntent.SetEnabled -> {
                graph.repo.setEnabled(intent.id, intent.enabled)
                graph.scheduler.reconcile("toggle")
                refreshReliability()
            }
            is TasksIntent.RunNow -> {
                graph.repo.enqueueManual(intent.id)
                graph.scheduler.reconcile("run_now")
            }
            TasksIntent.SelfTest -> selfTest()
        }
    }

    private fun start() {
        if (started) return
        started = true
        refreshReliability()
        viewModelScope.launch {
            combine(graph.repo.observeTasks(), graph.repo.observeRuns()) { tasks, runs -> tasks to runs }
                .collect { (tasks, runs) -> updateState { copy(tasks = tasks, runs = runs, isLoading = false) } }
        }
        viewModelScope.launch { graph.scheduler.reconcile("tasks_page_open") ; refreshReliability() }
    }

    private fun refreshReliability() {
        updateState { copy(reliability = Reliability.read(graph)) }
    }

    private suspend fun selfTest() {
        val task = TaskDraft.selfTest(System.currentTimeMillis(), ZoneId.systemDefault())
        graph.repo.saveTask(task)
        graph.scheduler.reconcile("self_test")
        refreshReliability()
        Logger.i(LOG_TAG, "self-test scheduled id=${task.id}")
        sendEffect(TasksEffect.Message("Self-test scheduled. Close Zafiro and wait about a minute for the notification."))
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_TasksViewModel"
    }
}
