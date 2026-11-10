package com.tricreta.zafiro.autonomy.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What to do with occurrences that were due while the phone was off, the process was killed, or the OS delayed the alarm. */
sealed interface MisfirePolicy {
    /** Only an on-time occurrence runs; anything later than [ON_TIME_SLACK] is recorded as missed. */
    data object Skip : MisfirePolicy

    /** Run the most recent missed occurrence once if it is at most [grace] late; older ones are recorded as missed (coalesced). */
    data class RunLate(val grace: Duration) : MisfirePolicy

    companion object {
        /** A delivered alarm is never exactly on time; anything within this slack counts as on time. */
        val ON_TIME_SLACK: Duration = Duration.ofSeconds(90)
    }
}

/** What to do when an occurrence is due while the previous run of the same task is still active. */
enum class OverlapPolicy { SKIP, QUEUE }

/** The scheduling-relevant part of a task. Prompt, tools, budget etc. live in the Android layer. */
data class TaskSpec(
    val id: String,
    val enabled: Boolean,
    val trigger: TimeTrigger?,
    val zone: ZoneId,
    val createdAt: Instant,
    val misfire: MisfirePolicy = MisfirePolicy.RunLate(Duration.ofHours(2)),
    val overlap: OverlapPolicy = OverlapPolicy.SKIP,
)

/** Persisted per-task progress: every occurrence at or before [lastHandled] has already become a run or a missed record. */
data class TaskCursor(val taskId: String, val lastHandled: Instant?)

enum class PlannedState { QUEUED, MISSED }

enum class MissReason { LATE, OVERLAP }

/**
 * A run to persist. (taskId, scheduledFor) is unique in storage, so replaying a plan is harmless.
 * For MISSED records [missedCount] is how many occurrences the record stands for.
 */
data class PlannedRun(
    val taskId: String,
    val scheduledFor: Instant,
    val state: PlannedState,
    val reason: MissReason? = null,
    val missedCount: Long = 0,
)

data class ReconcilePlan(
    val runs: List<PlannedRun>,
    val cursors: List<TaskCursor>,
    /** When the single exact alarm must fire next; null = nothing scheduled, cancel the alarm. */
    val nextWake: Instant?,
)

object Reconciler {

    /**
     * Pure function: given every task, its cursor, which tasks currently have an active run, and `now`,
     * decide which runs to create and when to wake next. Called on app start, boot, time/zone change,
     * permission change, alarm delivery and task edits — always the same code path (ADR-0003).
     */
    fun plan(
        tasks: List<TaskSpec>,
        cursors: Map<String, TaskCursor>,
        activeTaskIds: Set<String>,
        now: Instant,
    ): ReconcilePlan {
        val runs = ArrayList<PlannedRun>()
        val newCursors = ArrayList<TaskCursor>()
        var nextWake: Instant? = null

        for (task in tasks) {
            val trigger = task.trigger
            if (trigger == null) continue
            if (!task.enabled) {
                // Pausing is not "missing": keep the cursor at now so a later resume starts from the resume time.
                newCursors += TaskCursor(task.id, now)
                continue
            }

            val from = cursors[task.id]?.lastHandled ?: task.createdAt
            val window = TriggerMath.window(trigger, task.zone, from, now)
            var handled = from

            if (window.latest != null) {
                handled = window.latest
                val latest = window.latest
                val grace = when (val m = task.misfire) {
                    is MisfirePolicy.Skip -> MisfirePolicy.ON_TIME_SLACK
                    is MisfirePolicy.RunLate -> maxOf(m.grace, MisfirePolicy.ON_TIME_SLACK)
                }
                val lateness = Duration.between(latest, now)
                val runLatest = lateness <= grace
                val missed = window.count - if (runLatest) 1 else 0
                val lastMissed = if (runLatest) null else latest

                if (runLatest) {
                    if (task.overlap == OverlapPolicy.SKIP && task.id in activeTaskIds) {
                        runs += PlannedRun(task.id, latest, PlannedState.MISSED, MissReason.OVERLAP, 1)
                    } else {
                        runs += PlannedRun(task.id, latest, PlannedState.QUEUED)
                    }
                }
                if (missed > 0) {
                    // Coalesced record for the older occurrences. Keyed on the newest missed one so it is unique.
                    val key = lastMissed ?: previousOccurrence(trigger, task.zone, from, latest) ?: latest
                    runs += PlannedRun(task.id, key, PlannedState.MISSED, MissReason.LATE, missed)
                }
            }
            newCursors += TaskCursor(task.id, handled)

            TriggerMath.next(trigger, task.zone, maxOf(now, handled))?.let { due ->
                if (nextWake == null || due.isBefore(nextWake)) nextWake = due
            }
        }
        return ReconcilePlan(runs, newCursors, nextWake)
    }

    /** Newest occurrence in (from, before) — the key for the coalesced MISSED record when the latest one ran. */
    private fun previousOccurrence(trigger: TimeTrigger, zone: ZoneId, from: Instant, before: Instant): Instant? {
        val w = TriggerMath.window(trigger, zone, from, before.minusMillis(1))
        return w.latest
    }
}
