package com.tricreta.zafiro.autonomy.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.niki914.logging.Logger
import java.time.Instant

/**
 * The single exact alarm. It is a *projection* of the earliest persisted due time (ADR-0003): it never stores a schedule,
 * it only says "wake me at T so I can reconcile". Re-created on every reconcile, so losing it is always recoverable.
 */
class AlarmProjection(private val context: Context) {

    enum class Armed { EXACT, INEXACT, CANCELLED }

    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** Android 12+: the user may revoke "Alarms & reminders" at any time; revocation cancels our alarms. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun arm(next: Instant?): Armed {
        val pi = pendingIntent()
        if (next == null) {
            alarms.cancel(pi)
            return Armed.CANCELLED
        }
        val at = next.toEpochMilli()
        if (canScheduleExact()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return Armed.EXACT
            } catch (e: SecurityException) {
                Logger.w(LOG_TAG, "exact alarm refused, falling back to inexact: ${e.message}")
            }
        }
        // Not exact: can be late by minutes in Doze. The UI shows a warning; missed runs are recorded, never silent.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return Armed.INEXACT
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_ALARM)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val LOG_TAG = "AutonomyAlarm"
        const val REQUEST_CODE = 7100
    }
}
