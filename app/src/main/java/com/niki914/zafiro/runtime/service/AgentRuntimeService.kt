package com.niki914.zafiro.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.zafiro.app.PermissionHolder
import androidx.core.net.toUri
import com.niki914.logging.Logger
import com.niki914.store.HostApp
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.store.XIpcStoreRepository

import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.chat.ToolStatusLabels
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IAgentStoreService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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

    private val agent: Agent get() = requireService()
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
        private const val STORE_CHANNEL_NAME = "Zafiro"
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
            .setContentTitle("Zafiro Agent Runtime")
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
            } catch (e: Exception) {
                Logger.e(LOG_TAG, "submit failed linkToDeath error=${e.message}")
                return
            }

            agent.updateDraft { it.copy(text = q, images = emptyList()) }
            when (val startResult = agent.stream()) {
                TurnStart.Busy -> {
                    try {
                        cb.asBinder().unlinkToDeath(deathRecipient, 0)
                    } catch (_: Exception) {
                    }
                    Logger.w(LOG_TAG, "submit rejected activeTurnBusy=true")
                    sendError(cb, "Another turn is already in progress")
                    return
                }
                TurnStart.DraftEmpty -> {
                    try {
                        cb.asBinder().unlinkToDeath(deathRecipient, 0)
                    } catch (_: Exception) {
                    }
                    sendError(cb, "Query is blank")
                    return
                }
                TurnStart.Started -> {
                    Logger.i(LOG_TAG, "agent.stream started successfully")
                }
            }

            val job = scope.launch { executeTurn(cb) }
            val turn = ActiveTurn(cb, job)
            activeTurn.set(turn)
            Logger.i(LOG_TAG, "turn registered callbackLinked=true")
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
                    agent.stop()
                    Logger.i(LOG_TAG, "cancel done agent.stop completed")
                } catch (_: Exception) {
                }
            }
        }

        override fun resetConversation() {
            Logger.i(LOG_TAG, "reset conversation requested by host (ignored to protect shared conversation)")
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
            postNotificationImpl(t, c, createContentIntent(uri))
        }



        private fun postNotificationImpl(
            title: String,
            content: String,
            contentIntent: PendingIntent?
        ) {
            // 只读查询只经过 PermissionManager；业务方禁止直连原生权限 API（单测扫描兜底）
            if (PermissionHolder.get(this@AgentRuntimeService)
                .targetStatus(Permission.NOTIFICATION) != PermissionState.GRANTED
            ) return
            ensureNotificationChannel()

            val builder = NotificationCompat.Builder(this@AgentRuntimeService, STORE_CHANNEL_ID)
                .setSmallIcon(resolveSmallIcon())
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)

            contentIntent?.let { builder.setContentIntent(it) }
            NotificationManagerCompat.from(this@AgentRuntimeService).notify(
                notificationId(title, content),
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

        // MainActivity is launchMode=singleTask; NEW_TASK reuses the existing task
        // and routes through onNewIntent, so no duplicate activity is created
        private fun createAppLaunchPendingIntent(): PendingIntent? {
            val intent = Intent(this@AgentRuntimeService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                this@AgentRuntimeService,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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

        private fun notificationId(title: String, content: String): Int {
            var result = title.hashCode()
            result = 31 * result + content.hashCode()
            return result
        }
    }

    private suspend fun executeTurn(callback: IRenderFrameCallback) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "turn started")
        var firstFrameSent = false
        var lastRenderedText: String? = null
        var frameIndex = 0

        val labels = ToolStatusLabels(
            called = getString(AppR.string.ui_tool_status_called),
            running = getString(AppR.string.ui_tool_status_running),
            success = getString(AppR.string.ui_tool_status_success),
            failed = getString(AppR.string.ui_tool_status_failed),
        )

        fun resolveErrorMessage(code: TurnFailureCode?): String = when (code) {
            TurnFailureCode.ConfigRequired -> getString(AppR.string.ui_home_error_config_required_title)
            TurnFailureCode.IdleTimeout -> getString(AppR.string.ui_home_error_idle_timeout_title)
            else -> getString(AppR.string.runtime_error_internal)
        }

        val targetTurnId = agent.conversation.value.turns.lastOrNull()?.id
        Logger.i(LOG_TAG, "executeTurn targetTurnId=$targetTurnId")

        try {
            coroutineScope {
                val statusJob = launch {
                    agent.status.collect { status ->
                        if (status.phase == AgentPhase.Idle) {
                            return@collect
                        }
                    }
                }

                val conversationJob = launch {
                    agent.conversation.collect { conv ->
                        val turn = conv.turns.find { it.id == targetTurnId } ?: conv.turns.lastOrNull()
                        if (turn != null) {
                            val text = HostConversationProjector.render(turn, labels, ::resolveErrorMessage)
                            if (text != lastRenderedText || !firstFrameSent) {
                                frameIndex++
                                lastRenderedText = text
                                val isFirst = !firstFrameSent
                                firstFrameSent = true
                                if (isFirst) {
                                    Logger.i(
                                        LOG_TAG,
                                        "first render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} textLength=${text.length}"
                                    )
                                }
                                sendFrame(
                                    callback,
                                    RenderFrame(
                                        text = text,
                                        isFirst = isFirst,
                                        isFinal = false,
                                    ),
                                )
                            }
                        }
                    }
                }

                agent.status.first { it.phase == AgentPhase.Idle }
                conversationJob.cancel()
                statusJob.cancel()
            }

            val finalTurn = agent.conversation.value.turns.find { it.id == targetTurnId }
                ?: agent.conversation.value.turns.lastOrNull()
            val finalText = if (finalTurn != null) {
                HostConversationProjector.render(finalTurn, labels, ::resolveErrorMessage)
            } else {
                lastRenderedText.orEmpty()
            }
            Logger.i(
                LOG_TAG,
                "final render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} textLength=${finalText.length}"
            )
            sendFrame(
                callback,
                RenderFrame(
                    text = finalText,
                    isFirst = !firstFrameSent,
                    isFinal = true,
                ),
            )
            Logger.i(
                LOG_TAG,
                "turn completed elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        } catch (e: CancellationException) {
            Logger.i(
                LOG_TAG,
                "turn cancelled elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            throw e
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG,
                "turn failed elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "errorType=${e::class.simpleName} message=${e.message}"
            )
            sendFrame(
                callback,
                RenderFrame(
                    text = e.message ?: getString(AppR.string.runtime_error_internal),
                    isFirst = !firstFrameSent,
                    isFinal = true,
                ),
            )
        } finally {
            try {
                callback.asBinder().unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
            }
            if (activeTurn.get()?.callback === callback) {
                activeTurn.set(null)
            }
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
        // TODO: 宿主（如 Breeno）进程被系统强杀时，AMS 解绑导致后台 Service 失去绑定上下文，
        // 进而引发网络套接字受限或级联 Stream interrupted。此问题涉及跨进程服务生命周期与系统级保活架构，后续专门迭代处理。
        Logger.i(LOG_TAG, "binder died, cancelled host frame collector without stopping agent")
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
