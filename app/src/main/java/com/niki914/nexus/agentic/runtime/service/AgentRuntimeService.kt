package com.niki914.nexus.agentic.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import com.niki914.nexus.agentic.chat.LLMController
import com.niki914.nexus.agentic.chat.collectAsFull
import com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService
import com.niki914.nexus.agentic.runtime.ipc.IRenderFrameCallback
import com.niki914.nexus.agentic.runtime.ipc.RenderFrame
import com.niki914.nexus.ipc.HostApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class AgentRuntimeService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!validateCaller()) return null
        return StubImpl()
    }

    override fun onDestroy() {
        activeTurn.getAndSet(null)?.job?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeTurn = AtomicReference<ActiveTurn?>(null)

    private data class ActiveTurn(
        val callback: IRenderFrameCallback,
        val job: Job,
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "agent_runtime"
        private const val MAX_QUERY_LENGTH = 8192
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Runtime",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Agent Runtime")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private inner class StubImpl : IAgentRuntimeService.Stub() {

        override fun submit(query: String?, callback: IRenderFrameCallback?) {
            val q = query ?: return
            val cb = callback ?: return

            if (q.isBlank() || q.length > MAX_QUERY_LENGTH) {
                sendError(
                    cb, "Query is blank or exceeds maximum length of $MAX_QUERY_LENGTH characters",
                )
                return
            }

            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
                return
            }

            val job = scope.launch { executeTurn(q, cb) }
            val turn = ActiveTurn(cb, job)
            if (!activeTurn.compareAndSet(null, turn)) {
                job.cancel()
                try { cb.asBinder().unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
                sendError(cb, "Another turn is already in progress")
            }
        }

        override fun cancel() {
            val turn = activeTurn.getAndSet(null) ?: return
            turn.job.cancel()
            scope.launch {
                try { LLMController.stopCurrentRound() } catch (_: Exception) {}
            }
        }

        override fun resetConversation() {
            scope.launch {
                try { LLMController.resetConversation() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun executeTurn(query: String, callback: IRenderFrameCallback) {
        val thisTurn = activeTurn.get()
        try {
            LLMController.stream(query).collectAsFull { frame ->
                sendFrame(
                    callback,
                    RenderFrame(text = frame.text, isFirst = frame.isFirst, isFinal = frame.isFinal),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendFrame(
                callback,
                RenderFrame(text = e.message ?: "Internal error", isFirst = true, isFinal = true),
            )
        } finally {
            try { callback.asBinder().unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
            activeTurn.compareAndSet(thisTurn, null)
        }
    }

    private fun sendFrame(callback: IRenderFrameCallback, frame: RenderFrame) {
        try {
            callback.onFrame(frame)
        } catch (e: DeadObjectException) {
            handleBinderDeath()
        }
    }

    private fun sendError(callback: IRenderFrameCallback, message: String) {
        try {
            callback.onFrame(RenderFrame(text = message, isFirst = true, isFinal = true))
        } catch (_: DeadObjectException) {}
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDeath()
    }

    private fun handleBinderDeath() {
        val turn = activeTurn.getAndSet(null) ?: return
        turn.job.cancel()
        scope.launch {
            try { LLMController.stopCurrentRound() } catch (_: Exception) {}
        }
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
