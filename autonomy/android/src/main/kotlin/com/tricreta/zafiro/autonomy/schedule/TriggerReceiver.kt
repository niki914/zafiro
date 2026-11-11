package com.tricreta.zafiro.autonomy.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.tricreta.zafiro.autonomy.AutonomyGraph
import kotlinx.coroutines.launch

/**
 * Entry point for the exact alarm and for every event after which the alarm may be gone or wrong:
 * boot, app update, wall-clock change, time-zone change, "Alarms & reminders" granted.
 * All of them do the same thing: reconcile. (Exact alarms are exempt from background-FGS-start limits, so a due run
 * can start its foreground service from here.)
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: "unknown"
        // The JVM caches the default zone for the life of the process. Drop the cache when the phone says the zone changed,
        // so tasks that follow the device zone are recomputed against the NEW zone.
        if (reason == Intent.ACTION_TIMEZONE_CHANGED) java.util.TimeZone.setDefault(null)
        Logger.i(LOG_TAG, "trigger received: $reason")
        val pending = goAsync()
        val graph = AutonomyGraph.get(context)
        graph.scope.launch {
            try {
                graph.scheduler.reconcile(reason)
            } catch (t: Throwable) {
                Logger.e(LOG_TAG, "reconcile failed for $reason", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALARM = "com.tricreta.zafiro.autonomy.ALARM"
        private const val LOG_TAG = "AutonomyTrigger"
    }
}
