package com.tricreta.zafiro.autonomy.run

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.AutonomyNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground container for unattended runs (type specialUse: no runtime timeout, per Android docs).
 * Started from the exact alarm / boot receiver (both exempt from background-start limits) or from the UI.
 * Drains the queue one run at a time, holds a partial wake lock while working, then stops itself.
 */
class ScheduledRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AutonomyGraph.get(this)
        startForeground(AutonomyNotifier.RUNNING_ID, graph.notifier.runningNotification("Preparing…"))
        if (job?.isActive != true) {
            job = scope.launch { drain(graph) }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain(graph: AutonomyGraph) {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zafiro:autonomy_run").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
        try {
            while (true) {
                // Re-reconcile between runs: picks up anything that became due while a long run was executing.
                graph.scheduler.reconcile("drain", startService = false)
                val run = graph.repo.claimNext() ?: break
                Logger.i(LOG_TAG, "executing run ${run.id} for task ${run.taskId}")
                graph.executor.execute(run)
            }
        } catch (t: Throwable) {
            Logger.e(LOG_TAG, "drain failed", t)
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "AutonomyService"
        private const val WAKE_LOCK_MAX_MS = 45 * 60 * 1000L

        /** Safe to call from receivers (alarm/boot exemption) and from the foreground UI. Never throws. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScheduledRunService::class.java))
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException. The run stays QUEUED and is retried on the next reconcile.
                Logger.e(LOG_TAG, "could not start the run service", t)
            }
        }
    }
}
