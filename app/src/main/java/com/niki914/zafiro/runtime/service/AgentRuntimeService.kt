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

import com.niki914.zafiro.app.AppConversationRuntime
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.chat.FullTextProjector
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolStatusLabels
import com.niki914.zafiro.chat.runtime.CommandCaller
import com.niki914.zafiro.chat.runtime.CommandSurface
import com.niki914.zafiro.chat.runtime.ConversationOperation
import com.niki914.zafiro.chat.runtime.EntrySource
import com.niki914.zafiro.chat.runtime.ExecutionOwner
import com.niki914.zafiro.chat.runtime.TurnInput
import com.niki914.zafiro.chat.runtime.TurnKey
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IAgentStoreService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
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

    private class ActiveTurn(
        val callback: IRenderFrameCallback,
        /** 来源包装 Job：原回调/死亡/取消/清理的寿命所有者，也是执行 Job 的父 Job。 */
        val job: Job,
        /** 取消请求与执行身份发布的原子交接；绑定到本回合，不随 CAS 时序丢失。 */
        val stopHandoff: TurnStopHandoff,
    )

    /**
     * 单回合的停止交接（Group8 race fix）：把「取消请求」与「执行身份发布」两个随时序
     * 到达的事件收敛为一次原子判定，保证捕获回合的 scoped 引擎停止恰好调度一次。
     *
     * 状态只为当前 ActiveTurn 持有（有界，无全局缓存）：
     * - 取消先到：记 stopRequested，待 handle 发布时由发布方调度停止；
     * - 身份先到：requestStop 立即调度停止；
     * - 两者竞争：CAS 保证只有一个调用真正触发 [schedule]。
     * 调度沿用原 Service scope 异步执行，不等待整个包装协程，也不扩大寿命。
     */
    private class TurnStopHandoff(
        private val schedule: (TurnKey) -> Unit,
    ) {
        private data class State(
            val key: TurnKey? = null,
            val stopRequested: Boolean = false,
            val scheduled: Boolean = false,
        )

        private val state = AtomicReference(State())

        /** executor 发布执行身份；若取消请求已先到，触发一次 scoped 引擎停止。 */
        fun publishKey(key: TurnKey) {
            update { it.copy(key = key) }
            scheduleOnce()
        }

        /** 取消/死亡请求；若身份已先到，触发一次 scoped 引擎停止，否则等发布方触发。 */
        fun requestStop() {
            update { it.copy(stopRequested = true) }
            scheduleOnce()
        }

        private fun update(transform: (State) -> State) {
            while (true) {
                val current = state.get()
                if (state.compareAndSet(current, transform(current))) return
            }
        }

        private fun scheduleOnce() {
            while (true) {
                val current = state.get()
                if (current.scheduled || !current.stopRequested) return
                val key = current.key ?: return
                if (state.compareAndSet(current, current.copy(scheduled = true))) {
                    schedule(key)
                    return
                }
            }
        }
    }

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
            } catch (_: Exception) {
                return
            }

            // 保留原顺序：linkToDeath → 启动执行 → activeTurn CAS → 忙时 cancel/unlink/error。
            // LAZY 仅用于在启动前建立捕获的 owner（其 parentJob 即本来源包装 Job），
            // start 仍在 CAS 之前，不引入“CAS 成功才启动”的新门控。
            val stopHandoff = TurnStopHandoff { key ->
                // 捕获回合的 scoped 引擎停止：沿原 Service scope 异步执行，不等待包装协程。
                scope.launch {
                    try {
                        AppConversationRuntime.require().stopTurn(key)
                        Logger.i(LOG_TAG, "cancel done stopRoundCompleted=true")
                    } catch (_: Exception) {
                    }
                }
            }
            var owner: ExecutionOwner? = null
            val job = scope.launch(start = CoroutineStart.LAZY) {
                executeTurn(q, cb, owner!!, stopHandoff)
            }
            owner = ExecutionOwner(
                id = UUID.randomUUID().toString(),
                source = EntrySource.Host,
                parentJob = job,
                executionContext = scope.coroutineContext,
            )
            val turn = ActiveTurn(cb, job, stopHandoff)
            job.start()
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
            // 先取消捕获的来源包装 Job（含 submit 前），再原子交接：身份已发布则立即
            // 调度 scoped 引擎停止，未发布则由 executeTurn 发布时调度（恰好一次），
            // 不再因取消早于 turnKey 发布而静默丢弃显式停止。
            turn.job.cancel()
            Logger.i(LOG_TAG, "cancel requested")
            turn.stopHandoff.requestStop()
        }

        override fun resetConversation() {
            Logger.i(LOG_TAG, "reset conversation requested")
            scope.launch {
                val turn = activeTurn.getAndSet(null)
                turn?.job?.cancelAndJoin()
                try {
                    // 原 reset：只做显式 Runtime Reset（后端调原 LLMController.resetConversation）
                    AppConversationRuntime.require().operate(
                        ConversationOperation.Reset,
                        CommandCaller(CommandSurface.Host),
                    )
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

    private suspend fun executeTurn(
        query: String,
        callback: IRenderFrameCallback,
        owner: ExecutionOwner,
        stopHandoff: TurnStopHandoff,
    ) {
        val thisTurn = activeTurn.get()
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "turn started queryLength=${query.length}")
        var firstFrameSent = false
        try {
            val runtime = AppConversationRuntime.require()
            // 复用既有 FullTextProjector（每次执行独立实例），在 output 回调里直接 apply；
            // 不新增 channelFlow/buffer，帧内容、顺序与收尾条件与原 collectAsFull 一致。
            val projector = FullTextProjector(
                ToolStatusLabels(
                    called = getString(AppR.string.ui_tool_status_called),
                    running = getString(AppR.string.ui_tool_status_running),
                    success = getString(AppR.string.ui_tool_status_success),
                    failed = getString(AppR.string.ui_tool_status_failed),
                ),
            )
            val handle = runtime.submit(TurnInput(query), owner) { event ->
                // 数据变展示的边界（有 Context 的消费方负责本地化）：
                // 无原文的错误（ConfigRequired/IdleTimeout/守卫）在此翻译，
                // 有原文的错误原样透传；宿主进程只收渲染好的文本
                val mapped = if (event is LlmStreamEvent.Error && event.message == null) {
                    event.copy(
                        message = when (event.code) {
                            LlmErrorCode.ConfigRequired ->
                                getString(AppR.string.ui_home_error_config_required_title)
                            LlmErrorCode.IdleTimeout ->
                                getString(AppR.string.ui_home_error_idle_timeout_title)
                            else ->
                                getString(AppR.string.runtime_error_internal)
                        },
                    )
                } else {
                    event
                }
                projector.apply(mapped).forEach { frame ->
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
            }
            // submit 后发布执行身份：若取消请求早到，这里触发 scoped 引擎停止
            stopHandoff.publishKey(handle.key)
            // 原始业务/输出失败在 await(handle) 点原样交付，落在同一 try/catch 内
            runtime.await(handle)
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
                    isFirst = true,
                    isFinal = true
                ),
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
        // 先取消捕获的来源包装 Job，再原子交接：身份已发布则调度 scoped 定向引擎停止，
        // 未发布则由 handle 发布时调度。
        turn.job.cancel()
        turn.stopHandoff.requestStop()
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
