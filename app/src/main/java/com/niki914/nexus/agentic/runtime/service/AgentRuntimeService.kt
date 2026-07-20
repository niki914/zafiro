package com.niki914.nexus.agentic.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import com.niki914.nexus.agentic.chat.LLMController
import com.niki914.nexus.agentic.chat.collectAsFull
import com.niki914.nexus.agentic.runtime.ipc.AgentEvent
import com.niki914.nexus.agentic.runtime.ipc.IAgentEventCallback
import com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService
import com.niki914.nexus.ipc.HostApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
        if (turnMutex.isLocked) {
            scope.launch {
                try {
                    LLMController.stopCurrentRound()
                } catch (_: Exception) {}
            }
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turnMutex = Mutex()
    @Volatile
    private var activeCallback: IAgentEventCallback? = null

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

        override fun getStatus(): Int {
            return if (turnMutex.isLocked) 1 else 0
        }

        override fun submit(
            query: String?,
            params: Bundle?,
            callback: IAgentEventCallback?,
        ) {
            val q = query ?: return
            val cb = callback ?: return

            if (q.isBlank() || q.length > MAX_QUERY_LENGTH) {
                try {
                    cb.onEvent(
                        AgentEvent(
                            text = "Query is blank or exceeds maximum length of $MAX_QUERY_LENGTH characters",
                            isFirst = true,
                            isFinal = true,
                        )
                    )
                } catch (_: DeadObjectException) {}
                return
            }

            if (!turnMutex.tryLock()) {
                try {
                    cb.onEvent(
                        AgentEvent(
                            text = "Another turn is already in progress",
                            isFirst = true,
                            isFinal = true,
                        )
                    )
                } catch (_: DeadObjectException) {}
                return
            }

            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
                turnMutex.unlock()
                return
            }

            activeCallback = cb
            scope.launch { executeTurn(q) }
        }

        override fun cancel() {
            if (turnMutex.isLocked) {
                scope.launch {
                    try {
                        LLMController.stopCurrentRound()
                    } catch (_: Exception) {}
                }
            }
        }

        override fun resetConversation() {
            scope.launch {
                try {
                    LLMController.resetConversation()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun executeTurn(query: String) {
        try {
            LLMController.stream(query).collectAsFull { frame ->
                sendEvent(
                    AgentEvent(
                        text = frame.text,
                        isFirst = frame.isFirst,
                        isFinal = frame.isFinal,
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendEvent(
                AgentEvent(
                    text = e.message ?: "Internal error",
                    isFirst = true,
                    isFinal = true,
                )
            )
        } finally {
            activeCallback?.let { cb ->
                try {
                    cb.asBinder().unlinkToDeath(deathRecipient, 0)
                } catch (_: Exception) {}
            }
            activeCallback = null
            LLMController.resetRunningState()
            if (turnMutex.isLocked) {
                runCatching { turnMutex.unlock() }
            }
        }
    }

    private fun sendEvent(event: AgentEvent) {
        try {
            activeCallback?.onEvent(event)
        } catch (e: DeadObjectException) {
            handleBinderDeath()
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDeath()
    }

    private fun handleBinderDeath() {
        scope.launch {
            try {
                LLMController.stopCurrentRound()
            } catch (_: Exception) {}
            activeCallback = null
            if (turnMutex.isLocked) {
                runCatching { turnMutex.unlock() }
            }
            LLMController.resetRunningState()
        }
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
