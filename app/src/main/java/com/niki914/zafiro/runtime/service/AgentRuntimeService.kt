package com.niki914.zafiro.runtime.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.ToolStatusLabels
import com.niki914.zafiro.chat.collectAsFull
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IAgentStoreService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import com.niki914.store.HostApp
import com.niki914.store.XIpcStoreRepository
import com.niki914.store.displayNameFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import com.niki914.zafiro.app.R as AppR

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
        private const val LOG_TAG = "niki914_nexus_AgentRuntimeService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "agent_runtime"
        private const val MAX_QUERY_LENGTH = 8192
        private const val STORE_CHANNEL_ID = "nexus_xservice_default_channel"
        private const val STORE_CHANNEL_NAME = "Nexus"
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
        private val storeStub = StoreStubImpl()

        override fun getStoreBinder(): IBinder? {
            if (!validateCaller()) return null
            return storeStub
        }

        override fun submit(query: String?, callback: IRenderFrameCallback?) {
            val q = query ?: return
            val cb = callback ?: return

            if (q.isBlank() || q.length > MAX_QUERY_LENGTH) {
                Logger.w(
                    LOG_TAG,
                    "submit rejected queryLength=${q.length} maxLength=$MAX_QUERY_LENGTH " +
                        "reason=${if (q.isBlank()) "blank" else "tooLong"}"
                )
                sendError(
                    cb, "Query is blank or exceeds maximum length of $MAX_QUERY_LENGTH characters",
                )
                return
            }
            Logger.i(LOG_TAG, "submit accepted queryLength=${q.length}")

            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
                return
            }

            val job = scope.launch { executeTurn(q, cb) }
            val turn = ActiveTurn(cb, job)
            if (!activeTurn.compareAndSet(null, turn)) {
                job.cancel()
                try {
                    cb.asBinder().unlinkToDeath(deathRecipient, 0)
                } catch (_: Exception) {
                }
                Logger.w(LOG_TAG, "submit rejected activeTurnBusy=true")
                sendError(cb, "Another turn is already in progress")
            } else {
                Logger.i(LOG_TAG, "turn registered callbackLinked=true")
            }
        }

        override fun cancel() {
            val turn = activeTurn.getAndSet(null)
            if (turn == null) {
                Logger.i(LOG_TAG, "cancel ignored noActiveTurn=true")
                return
            }
            turn.job.cancel()
            Logger.i(LOG_TAG, "cancel requested")
            scope.launch {
                try {
                    LLMController.stopCurrentRound()
                    Logger.i(LOG_TAG, "cancel done stopRoundCompleted=true")
                } catch (_: Exception) {
                }
            }
        }

        override fun resetConversation() {
            Logger.i(LOG_TAG, "reset conversation requested")
            scope.launch {
                val turn = activeTurn.getAndSet(null)
                turn?.job?.cancelAndJoin()
                try {
                    LLMController.resetConversation()
                    Logger.i(LOG_TAG, "reset conversation done")
                } catch (_: Exception) {
                }
            }
        }
    }

    private inner class StoreStubImpl : IAgentStoreService.Stub() {
        override fun readStore(storeId: String?): String? {
            if (!validateCaller()) return null
            val id = storeId ?: return null
            val startedAtMs = System.currentTimeMillis()
            val json = runBlocking {
                XIpcStoreRepository.readJson(this@AgentRuntimeService, id)
            }
            Logger.d(
                LOG_TAG,
                "StoreStub.readStore storeId=$id result=${json != null} " +
                    "jsonLength=${json?.length ?: 0} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return json
        }

        override fun writeStore(storeId: String?, json: String?) {
            if (!validateCaller()) return
            val id = storeId ?: return
            val j = json ?: return
            val startedAtMs = System.currentTimeMillis()
            runBlocking {
                XIpcStoreRepository.writeJson(this@AgentRuntimeService, id, j)
            }
            Logger.i(
                LOG_TAG,
                "StoreStub.writeStore storeId=$id jsonLength=${j.length} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }

        override fun mutateStore(storeId: String?, path: String?, valueJson: String?): String? {
            if (!validateCaller()) return null
            val id = storeId ?: return null
            val p = path ?: return null
            val v = valueJson ?: return null
            val startedAtMs = System.currentTimeMillis()
            val updatedJson = runBlocking {
                XIpcStoreRepository.mutateJson(this@AgentRuntimeService, id, p, v)
            }
            Logger.i(
                LOG_TAG,
                "StoreStub.mutateStore storeId=$id path=$p result=${updatedJson != null} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return updatedJson
        }

        override fun postNotification(title: String?, content: String?, uri: String?) {
            if (!validateCaller()) return
            val t = title ?: return
            val c = content ?: return
            postNotificationImpl(t, c, uri)
        }

        override fun postNetworkErrorNotification() {
            if (!validateCaller()) return
            val ctx = this@AgentRuntimeService
            postNotificationImpl(
                ctx.getString(AppR.string.notif_network_error_title),
                ctx.getString(AppR.string.notif_network_error_body),
                null
            )
        }

        override fun postUnsupportedVersionNotification(
            hostPackageName: String?,
            hostVersion: String?
        ) {
            if (!validateCaller()) return
            val ctx = this@AgentRuntimeService
            val hostApp = HostApp.fromPackageName(hostPackageName)
            val hostName = hostApp?.let { ctx.displayNameFor(it) }
                ?: ctx.getString(AppR.string.fallback_assistant_name)
            val version = hostVersion ?: ""
            val issueUri = Uri.Builder()
                .scheme("https")
                .authority("github.com")
                .path("/niki914/agentic-nexus/issues/new")
                .appendQueryParameter(
                    "title",
                    ctx.getString(AppR.string.notif_unsupported_issue_title)
                )
                .appendQueryParameter(
                    "body",
                    ctx.getString(AppR.string.notif_unsupported_issue_body, hostName, version)
                )
                .build()
                .toString()
            postNotificationImpl(
                ctx.getString(AppR.string.notif_unsupported_version_title),
                ctx.getString(AppR.string.notif_unsupported_version_body, hostName, version),
                issueUri
            )
        }

        private fun postNotificationImpl(title: String, content: String, uri: String?) {
            fun hasPermission(): Boolean {
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                    this@AgentRuntimeService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }
            if (!hasPermission()) return
            ensureNotificationChannel()

            val builder = NotificationCompat.Builder(this@AgentRuntimeService, STORE_CHANNEL_ID)
                .setSmallIcon(resolveSmallIcon())
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)

            createContentIntent(uri)?.let { contentIntent ->
                builder.setContentIntent(contentIntent)
            }
            NotificationManagerCompat.from(this@AgentRuntimeService).notify(
                notificationId(title, content, uri),
                builder.build()
            )
        }

        private fun ensureNotificationChannel() {
            val manager =
                this@AgentRuntimeService.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                STORE_CHANNEL_ID,
                STORE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        private fun createContentIntent(uri: String?): PendingIntent? {
            if (uri.isNullOrBlank()) {
                return null
            }
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val resolved =
                this@AgentRuntimeService.packageManager.resolveActivity(intent, 0) ?: return null
            val pendingIntentFlags =
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(
                this@AgentRuntimeService,
                resolved.activityInfo.packageName.hashCode(),
                intent,
                pendingIntentFlags
            )
        }

        private fun resolveSmallIcon(): Int {
            return this@AgentRuntimeService.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_dialog_info
        }

        private fun notificationId(title: String, content: String, uri: String?): Int {
            var result = title.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + (uri?.hashCode() ?: 0)
            return result
        }
    }

    private suspend fun executeTurn(query: String, callback: IRenderFrameCallback) {
        val thisTurn = activeTurn.get()
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "turn started queryLength=${query.length}")
        var firstFrameSent = false
        try {
            LLMController.stream(query, this@AgentRuntimeService).collectAsFull(
                labels = ToolStatusLabels(
                    called = getString(AppR.string.ui_tool_status_called),
                    running = getString(AppR.string.ui_tool_status_running),
                    success = getString(AppR.string.ui_tool_status_success),
                    failed = getString(AppR.string.ui_tool_status_failed),
                )
            ) { frame ->
                if (!firstFrameSent) {
                    firstFrameSent = true
                    Logger.i(
                        LOG_TAG,
                        "first render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                            "textLength=${frame.text.length}"
                    )
                }
                if (frame.isFinal) {
                    Logger.i(
                        LOG_TAG,
                        "final render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                            "textLength=${frame.text.length}"
                    )
                }
                sendFrame(
                    callback,
                    RenderFrame(
                        text = frame.text,
                        isFirst = frame.isFirst,
                        isFinal = frame.isFinal
                    ),
                )
            }
            Logger.i(LOG_TAG, "turn completed elapsedMs=${System.currentTimeMillis() - startedAtMs}")
        } catch (e: CancellationException) {
            Logger.i(LOG_TAG, "turn cancelled elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            throw e
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG,
                "turn failed elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                    "errorType=${e::class.simpleName} message=${e.message}"
            )
            sendFrame(
                callback,
                RenderFrame(text = e.message ?: getString(AppR.string.runtime_error_internal), isFirst = true, isFinal = true),
            )
        } finally {
            try {
                callback.asBinder().unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
            }
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
        } catch (_: DeadObjectException) {
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDeath()
    }

    private fun handleBinderDeath() {
        val turn = activeTurn.getAndSet(null) ?: return
        turn.job.cancel()
        scope.launch {
            try {
                LLMController.stopCurrentRound()
            } catch (_: Exception) {
            }
        }
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
