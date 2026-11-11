package com.tricreta.zafiro.autonomy.schedule

import android.content.Context
import com.niki914.logging.Logger
import com.tricreta.zafiro.autonomy.data.AutonomyRepository
import com.tricreta.zafiro.autonomy.run.ActiveRuns
import com.tricreta.zafiro.autonomy.run.ScheduledRunService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class SchedulerSnapshot(
    val nextWake: Instant?,
    val armed: AlarmProjection.Armed,
    val reconciledAtMs: Long,
    val reason: String,
)

/** Serialises reconcile → arm alarm → start the run service if work is waiting. */
class AutonomyScheduler(
    private val context: Context,
    private val repo: AutonomyRepository,
    private val alarm: AlarmProjection,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun reconcile(reason: String, startService: Boolean = true): SchedulerSnapshot = mutex.withLock {
        val swept = repo.sweepOrphans(ActiveRuns.ids())
        if (swept > 0) Logger.w(LOG_TAG, "marked $swept orphaned run(s) as interrupted")
        val now = Instant.now()
        val outcome = repo.reconcile(now)
        val armed = alarm.arm(outcome.nextWake)
        prefs.edit()
            .putLong(KEY_NEXT_WAKE, outcome.nextWake?.toEpochMilli() ?: -1L)
            .putString(KEY_ARMED, armed.name)
            .putLong(KEY_RECONCILED_AT, now.toEpochMilli())
            .putString(KEY_REASON, reason)
            .apply()
        Logger.i(LOG_TAG, "reconcile($reason): next=${outcome.nextWake} armed=$armed queued=${outcome.queued} new=${outcome.newRuns}")
        if (startService && outcome.queued > 0) ScheduledRunService.start(context)
        SchedulerSnapshot(outcome.nextWake, armed, now.toEpochMilli(), reason)
    }

    /** Last published state, for the status card. Cheap, no I/O beyond SharedPreferences. */
    fun lastSnapshot(): SchedulerSnapshot? {
        val at = prefs.getLong(KEY_RECONCILED_AT, -1L)
        if (at < 0) return null
        val next = prefs.getLong(KEY_NEXT_WAKE, -1L)
        return SchedulerSnapshot(
            nextWake = next.takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
            armed = runCatching { AlarmProjection.Armed.valueOf(prefs.getString(KEY_ARMED, "") ?: "") }
                .getOrDefault(AlarmProjection.Armed.CANCELLED),
            reconciledAtMs = at,
            reason = prefs.getString(KEY_REASON, "") ?: "",
        )
    }

    private companion object {
        const val LOG_TAG = "AutonomyScheduler"
        const val PREFS = "autonomy_scheduler"
        const val KEY_NEXT_WAKE = "next_wake_ms"
        const val KEY_ARMED = "armed"
        const val KEY_RECONCILED_AT = "reconciled_at_ms"
        const val KEY_REASON = "reason"
    }
}
