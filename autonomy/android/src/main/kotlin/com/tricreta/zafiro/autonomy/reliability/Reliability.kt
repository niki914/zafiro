package com.tricreta.zafiro.autonomy.reliability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.schedule.AlarmProjection
import java.time.Instant

/** What decides whether a scheduled task can wake the phone on time, read fresh each time (the user can change it any moment). */
data class ReliabilityStatus(
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val nextWake: Instant?,
    val armed: AlarmProjection.Armed?,
    val lastCheckMs: Long?,
    val lastCheckReason: String?,
)

object Reliability {

    fun read(graph: AutonomyGraph): ReliabilityStatus {
        val ctx = graph.appContext
        val snap = graph.scheduler.lastSnapshot()
        return ReliabilityStatus(
            exactAlarmAllowed = graph.alarm.canScheduleExact(),
            ignoringBatteryOptimizations = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            nextWake = snap?.nextWake,
            armed = snap?.armed,
            lastCheckMs = snap?.reconciledAtMs,
            lastCheckReason = snap?.reason,
        )
    }

    /** "Alarms & reminders" page for this app (Android 12+). Null where the setting does not exist. */
    fun exactAlarmSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    /** Asks to be exempt from battery optimisation; the caller falls back to the general list if the system refuses. */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
