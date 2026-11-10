package com.tricreta.zafiro.autonomy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tricreta.zafiro.autonomy.core.RunState
import com.tricreta.zafiro.autonomy.run.ApprovalReceiver
import com.tricreta.zafiro.autonomy.ui.AutonomyActivity

/** Own channels so no upstream notification enum has to change. */
class AutonomyNotifier(private val context: Context) {

    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, context.getString(R.string.autonomy_channel_running), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULTS, context.getString(R.string.autonomy_channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVALS, context.getString(R.string.autonomy_channel_approvals), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun runningNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_autonomy)
            .setContentTitle("Zafiro is running a scheduled task")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    fun updateRunning(text: String) = safeNotify(RUNNING_ID, runningNotification(text))

    fun postResult(runId: Long, taskName: String, state: RunState, text: String) {
        val title = when (state) {
            RunState.SUCCEEDED -> "$taskName: done"
            RunState.LIMIT_REACHED -> "$taskName: stopped at its limit"
            RunState.NEEDS_HUMAN -> "$taskName: needs you"
            RunState.CANCELLED -> "$taskName: cancelled"
            RunState.INTERRUPTED -> "$taskName: interrupted"
            else -> "$taskName: failed"
        }
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_autonomy)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        safeNotify(RESULT_BASE + (runId % 1000).toInt(), n)
    }

    fun postApproval(requestId: String, taskName: String, detail: String) {
        val n = NotificationCompat.Builder(context, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_autonomy)
            .setContentTitle("$taskName needs approval")
            .setContentText(detail.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(1000)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Allow", approvalIntent(requestId, ApprovalReceiver.ACTION_ALLOW))
            .addAction(0, "Deny", approvalIntent(requestId, ApprovalReceiver.ACTION_DENY))
            .build()
        safeNotify(approvalNotificationId(requestId), n)
    }

    fun cancelApproval(requestId: String) = nm.cancel(approvalNotificationId(requestId))

    private fun approvalIntent(requestId: String, action: String): PendingIntent {
        val intent = Intent(context, ApprovalReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("autonomy://approval/$requestId/$action"))
            .putExtra(ApprovalReceiver.EXTRA_REQUEST_ID, requestId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, AutonomyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, n: Notification) {
        // POST_NOTIFICATIONS may be denied on Android 13+; a denied permission must never crash a run.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(id, n) }
        }
    }

    companion object {
        const val RUNNING_ID = 71001
        private const val RESULT_BASE = 72000
        const val CH_RUNNING = "autonomy_running"
        const val CH_RESULTS = "autonomy_results"
        const val CH_APPROVALS = "autonomy_approvals"

        fun approvalNotificationId(requestId: String): Int = 73000 + (requestId.hashCode() and 0x3FF)
    }
}
