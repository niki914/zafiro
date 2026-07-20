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
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.runtime.ipc.AgentEvent
import com.niki914.nexus.agentic.runtime.ipc.IAgentEventCallback
import com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService
import com.niki914.nexus.ipc.HostApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

class AgentRuntimeService : Service() {

    // --- Lifecycle ---

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
        // Cancel active turn if any
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

    // --- Internal state ---

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turnMutex = Mutex()
    @Volatile
    private var activeCallback: IAgentEventCallback? = null
    private val eventSequence = AtomicInteger(0)

    // --- Text merge buffer ---

    private val textBuffer = StringBuilder()
    private var isFirstText = true
    private var flushJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "agent_runtime"
        private const val MAX_QUERY_LENGTH = 8192
        private const val FLUSH_INTERVAL_MS = 80L
    }

    // --- Notification ---

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

    // --- AIDL Stub ---

    private inner class StubImpl : IAgentRuntimeService.Stub() {

        override fun getStatus(): Int {
            return if (turnMutex.isLocked) 1 else 0
        }

        override fun submit(
            query: String?,
            params: Bundle?,
            callback: IAgentEventCallback?,
        ) {
            // Guard: client always passes non-null query and callback
            val q = query ?: return
            val cb = callback ?: return

            // Validate query
            if (q.isBlank() || q.length > MAX_QUERY_LENGTH) {
                try {
                    cb.onEvent(
                        AgentEvent(
                            sequence = 0,
                            timestamp = System.currentTimeMillis(),
                            eventType = "Error",
                            errorCode = "INVALID_REQUEST",
                            errorMessage = "Query is blank or exceeds maximum length of $MAX_QUERY_LENGTH characters",
                        )
                    )
                } catch (_: DeadObjectException) {
                    // Client died while sending error
                }
                return
            }

            // Try to acquire the turn lock
            if (!turnMutex.tryLock()) {
                try {
                    cb.onEvent(
                        AgentEvent(
                            sequence = 0,
                            timestamp = System.currentTimeMillis(),
                            eventType = "Error",
                            errorCode = "TURN_CONFLICT",
                            errorMessage = "Another turn is already in progress",
                        )
                    )
                } catch (_: DeadObjectException) {
                    // Client died while sending error
                }
                return
            }

            // Register death recipient on the callback binder
            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
                // Client binder already dead; release lock and return
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

    // --- Turn execution ---

    private suspend fun executeTurn(query: String) {
        try {
            eventSequence.set(0)
            synchronized(textBuffer) {
                textBuffer.clear()
            }
            isFirstText = true

            LLMController.stream(query).collect { event ->
                mapAndSend(event)
            }
        } catch (e: CancellationException) {
            flushTextBuffer()
            sendEvent(
                AgentEvent(
                    sequence = eventSequence.getAndIncrement(),
                    timestamp = System.currentTimeMillis(),
                    eventType = "Cancelled",
                )
            )
            throw e
        } catch (e: Exception) {
            sendEvent(
                AgentEvent(
                    sequence = eventSequence.getAndIncrement(),
                    timestamp = System.currentTimeMillis(),
                    eventType = "Error",
                    errorCode = "INTERNAL_ERROR",
                    errorMessage = e.message,
                )
            )
        } finally {
            flushTextBuffer()
            // Unlink death recipient
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

    // --- Event mapping ---

    private suspend fun mapAndSend(event: LlmStreamEvent) {
        when (event) {
            LlmStreamEvent.RoundStarted -> {
                flushTextBuffer()
                isFirstText = true
            }

            is LlmStreamEvent.TextDelta -> {
                synchronized(textBuffer) {
                    textBuffer.append(event.delta)
                }
                scheduleFlush()
            }

            is LlmStreamEvent.ToolRunning -> {
                flushTextBuffer()
                sendEvent(
                    AgentEvent(
                        sequence = eventSequence.getAndIncrement(),
                        timestamp = System.currentTimeMillis(),
                        eventType = "ToolRunning",
                        toolName = event.call.name,
                        toolLabel = event.call.label,
                    )
                )
            }

            is LlmStreamEvent.ToolSucceeded -> {
                flushTextBuffer()
                sendEvent(
                    AgentEvent(
                        sequence = eventSequence.getAndIncrement(),
                        timestamp = System.currentTimeMillis(),
                        eventType = "ToolSucceeded",
                        toolName = event.call.name,
                        toolOutput = event.outputText,
                    )
                )
            }

            is LlmStreamEvent.ToolFailed -> {
                flushTextBuffer()
                sendEvent(
                    AgentEvent(
                        sequence = eventSequence.getAndIncrement(),
                        timestamp = System.currentTimeMillis(),
                        eventType = "ToolFailed",
                        toolName = event.call.name,
                        toolError = event.message,
                    )
                )
            }

            is LlmStreamEvent.Completed -> {
                flushTextBuffer()
                sendEvent(
                    AgentEvent(
                        sequence = eventSequence.getAndIncrement(),
                        timestamp = System.currentTimeMillis(),
                        eventType = "Completed",
                        text = event.fullText,
                    )
                )
            }

            is LlmStreamEvent.Error -> {
                flushTextBuffer()
                val code = when (event.code) {
                    com.niki914.nexus.agentic.chat.LlmErrorCode.ConfigRequired -> "CONFIG_REQUIRED"
                    com.niki914.nexus.agentic.chat.LlmErrorCode.TurnConflict -> "TURN_CONFLICT"
                    null -> "LLM_REQUEST_FAILED"
                }
                sendEvent(
                    AgentEvent(
                        sequence = eventSequence.getAndIncrement(),
                        timestamp = System.currentTimeMillis(),
                        eventType = "Error",
                        errorCode = code,
                        errorMessage = event.message,
                    )
                )
            }
        }
    }

    private suspend fun flushTextBuffer() {
        flushJob?.cancel()
        flushJob = null
        val text = synchronized(textBuffer) {
            if (textBuffer.isEmpty()) return
            val t = textBuffer.toString()
            textBuffer.clear()
            t
        }
        val first = isFirstText
        isFirstText = false
        sendEvent(
            AgentEvent(
                sequence = eventSequence.getAndIncrement(),
                timestamp = System.currentTimeMillis(),
                eventType = "TextDelta",
                text = text,
                isFirst = first,
                isFinal = false,
            )
        )
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushTextBuffer()
        }
    }

    private fun sendEvent(event: AgentEvent) {
        try {
            activeCallback?.onEvent(event)
        } catch (e: DeadObjectException) {
            handleBinderDeath()
        }
    }

    // --- Binder death ---

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDeath()
    }

    private fun handleBinderDeath() {
        scope.launch {
            try {
                LLMController.stopCurrentRound()
            } catch (_: Exception) {}
            try {
                TerminalSessionPool.closeAll()
            } catch (_: Exception) {}
            activeCallback = null
            if (turnMutex.isLocked) {
                runCatching { turnMutex.unlock() }
            }
            LLMController.resetRunningState()
        }
    }

    // --- Caller validation ---

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
