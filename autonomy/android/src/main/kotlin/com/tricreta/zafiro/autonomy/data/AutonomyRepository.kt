package com.tricreta.zafiro.autonomy.data

import androidx.room.withTransaction
import com.tricreta.zafiro.autonomy.core.PlannedState
import com.tricreta.zafiro.autonomy.core.Reconciler
import com.tricreta.zafiro.autonomy.core.RunState
import com.tricreta.zafiro.autonomy.core.RunStateMachine
import com.tricreta.zafiro.autonomy.core.TaskCursor
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class ReconcileOutcome(val nextWake: Instant?, val queued: Int, val newRuns: Int)

/** The only writer of scheduling state. Everything is idempotent; the database is the source of truth (ADR-0003). */
class AutonomyRepository(
    private val db: AutonomyDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.dao()

    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeTasks()
    fun observeRuns(limit: Int = 60): Flow<List<RunEntity>> = dao.observeRuns(limit)

    suspend fun task(id: String): TaskEntity? = dao.task(id)
    suspend fun tasks(): List<TaskEntity> = dao.tasks()
    suspend fun events(runId: Long): List<RunEventEntity> = dao.events(runId)
    suspend fun run(id: Long): RunEntity? = dao.run(id)

    /** Insert or edit. The cursor is moved to "now" so an edit never produces a burst of "missed" history. */
    suspend fun saveTask(task: TaskEntity) = db.withTransaction {
        dao.upsertTask(task)
        dao.upsertCursor(CursorEntity(task.id, nowMs()))
    }

    suspend fun deleteTask(id: String) = db.withTransaction {
        dao.deleteTask(id)
        dao.deleteCursor(id)
        dao.deleteRunsOfTask(id)
        dao.pruneOrphanEvents()
    }

    /** Pausing is not missing, and resuming starts from the resume time. */
    suspend fun setEnabled(id: String, enabled: Boolean) = db.withTransaction {
        dao.setEnabled(id, enabled, nowMs())
        dao.upsertCursor(CursorEntity(id, nowMs()))
    }

    /**
     * Turns time into runs: creates QUEUED/MISSED records (unique per task+due time, so replays are harmless),
     * advances cursors, and returns when the single exact alarm must fire next.
     */
    suspend fun reconcile(now: Instant): ReconcileOutcome = db.withTransaction {
        val specs = dao.tasks().map { it.toSpec() }
        val names = dao.tasks().associate { it.id to it.name }
        val cursors = dao.cursors().associate { it.taskId to TaskCursor(it.taskId, it.lastHandledMs?.let(Instant::ofEpochMilli)) }
        val plan = Reconciler.plan(specs, cursors, dao.activeTaskIds().toSet(), now)
        var inserted = 0
        for (r in plan.runs) {
            val id = dao.insertRun(
                RunEntity(
                    taskId = r.taskId,
                    taskName = names[r.taskId].orEmpty(),
                    scheduledForMs = r.scheduledFor.toEpochMilli(),
                    state = if (r.state == PlannedState.QUEUED) RunState.QUEUED.name else RunState.MISSED.name,
                    reason = r.reason?.name,
                    missedCount = r.missedCount,
                    createdAtMs = now.toEpochMilli(),
                    finishedAtMs = if (r.state == PlannedState.MISSED) now.toEpochMilli() else null,
                    summary = if (r.state == PlannedState.MISSED) missedText(r.reason?.name, r.missedCount) else null,
                )
            )
            if (id != -1L) inserted++
        }
        for (c in plan.cursors) dao.upsertCursor(CursorEntity(c.taskId, c.lastHandled?.toEpochMilli()))
        ReconcileOutcome(plan.nextWake, dao.queuedCount(), inserted)
    }

    private fun missedText(reason: String?, count: Long): String = when (reason) {
        "OVERLAP" -> "Skipped: the previous run of this task was still active."
        else -> "Missed $count scheduled run(s): the phone was off, the app was stopped, or Android delayed the alarm beyond the allowed grace."
    }

    /** A manual "run now" is just a QUEUED run due immediately. */
    suspend fun enqueueManual(taskId: String): Long? {
        val task = dao.task(taskId) ?: return null
        val now = nowMs()
        val id = dao.insertRun(
            RunEntity(
                taskId = taskId, taskName = task.name, scheduledForMs = now, state = RunState.QUEUED.name,
                createdAtMs = now, manual = true,
            )
        )
        return id.takeIf { it != -1L }
    }

    /** Atomically take the oldest queued run (QUEUED → STARTING). Null if nothing is waiting. */
    suspend fun claimNext(): RunEntity? = db.withTransaction {
        val next = dao.nextQueued() ?: return@withTransaction null
        val changed = dao.transition(next.id, RunState.QUEUED.name, RunState.STARTING.name, nowMs())
        if (changed == 1) dao.run(next.id) else null
    }

    suspend fun markRunning(id: Long): Boolean {
        RunStateMachine.require(RunState.STARTING, RunState.RUNNING)
        return dao.transition(id, RunState.STARTING.name, RunState.RUNNING.name, nowMs()) == 1
    }

    suspend fun heartbeat(id: Long, steps: Int) = dao.heartbeat(id, nowMs(), steps)

    suspend fun finish(
        id: Long, state: RunState, summary: String?, error: String?, conversationId: String?, steps: Int,
    ) {
        require(state.terminal) { "finish needs a terminal state, got $state" }
        dao.finish(id, state.name, nowMs(), summary?.take(MAX_SUMMARY), error?.take(MAX_SUMMARY), conversationId, steps)
        dao.pruneRuns(KEEP_RUNS)
        dao.pruneOrphanEvents()
    }

    suspend fun event(runId: Long, kind: String, text: String) =
        dao.insertEvent(RunEventEntity(runId = runId, tsMs = nowMs(), kind = kind, text = text.take(MAX_EVENT)))

    /**
     * A run left in STARTING/RUNNING/PAUSED whose owner is not alive in this process was killed with the process
     * (OEM killer, crash, swipe-away + force-stop). Record it instead of leaving it "running" forever.
     */
    suspend fun sweepOrphans(ownedRunIds: Set<Long>): Int {
        val now = nowMs()
        // Not owned by this process AND no heartbeat for a while (the executor beats every second): the second condition
        // closes the few-millisecond window between "claimed" and "registered as active" in a live process.
        val orphans = dao.inFlight().filter {
            it.id !in ownedRunIds && now - (it.heartbeatMs ?: it.startedAtMs ?: it.createdAtMs) > ORPHAN_STALE_MS
        }
        for (o in orphans) {
            dao.finish(
                o.id, RunState.INTERRUPTED.name, nowMs(), o.summary,
                "The app process ended while this run was in progress (killed by the system or crashed).",
                null, o.steps,
            )
            dao.insertEvent(RunEventEntity(runId = o.id, tsMs = nowMs(), kind = "error", text = "interrupted: process ended mid-run"))
        }
        return orphans.size
    }

    private companion object {
        const val MAX_SUMMARY = 4000
        const val MAX_EVENT = 600
        const val KEEP_RUNS = 300
        const val ORPHAN_STALE_MS = 20_000L
    }
}
