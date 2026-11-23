package com.niki914.zafiro.runtime.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.niki914.logging.Logger
import com.niki914.store.XIpcBridge
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IAgentStoreService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class ServiceUnavailableException :
    IllegalStateException("Agent runtime service is not connected")

class AgentRuntimeClient(private val context: Context) : AssistantTextSource,
    XIpcBridge.StoreClient {

    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Reconnecting,
        Rejected,
        Unavailable,
    }

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var service: IAgentRuntimeService? = null
    private var storeService: IAgentStoreService? = null
    private var binder: IBinder? = null
    private var bound = false
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var retryCount = 0
    private val connectMutex = Mutex()
    private val activeRequests = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleDisconnectRunnable = Runnable {
        if (activeRequests.get() == 0 && bound) {
            Logger.i(LOG_TAG, "idle timeout reached, disconnecting AgentRuntimeService")
            disconnect()
        }
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_AgentRuntimeClient"
        private const val BIND_ACTION = "com.niki914.zafiro.runtime.BIND"
        private const val SERVICE_CLASS =
            "com.niki914.zafiro.runtime.service.AgentRuntimeService"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val IDLE_DISCONNECT_DELAY_MS = 60_000L
    }

    private fun cancelIdleDisconnect() {
        mainHandler.removeCallbacks(idleDisconnectRunnable)
    }

    private fun scheduleIdleDisconnect() {
        mainHandler.removeCallbacks(idleDisconnectRunnable)
        if (activeRequests.get() == 0 && bound) {
            mainHandler.postDelayed(idleDisconnectRunnable, IDLE_DISCONNECT_DELAY_MS)
        }
    }

    suspend fun ensureConnected(timeoutMs: Long = 5_000): Boolean {
        cancelIdleDisconnect()
        if (service != null && storeService != null && _connectionState.value == ConnectionState.Connected) {
            return true
        }
        return connectMutex.withLock {
            if (service != null && storeService != null && _connectionState.value == ConnectionState.Connected) {
                true
            } else {
                connectAndAwait(timeoutMs)
            }
        }
    }

    // --- Connection ---

    fun connect(): Boolean {
        retryCount = 0
        _connectionState.value = ConnectionState.Connecting
        Logger.d(LOG_TAG, "connect attempt")

        deathRecipient = IBinder.DeathRecipient { handleBinderDeath() }

        val intent = Intent(BIND_ACTION).apply {
            setClassName(BuildConfig.APPLICATION_ID, SERVICE_CLASS)
        }

        val result = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "connect bind failed error=${e.message}")
            _connectionState.value = ConnectionState.Unavailable
            return false
        }

        if (result) {
            bound = true
            Logger.i(LOG_TAG, "connect bound")
        } else {
            Logger.w(LOG_TAG, "connect bind rejected")
            _connectionState.value = ConnectionState.Unavailable
        }
        return result
    }

    suspend fun connectAndAwait(timeoutMs: Long = 5_000): Boolean {
        val startedAtMs = System.currentTimeMillis()
        connect()
        val state = withTimeoutOrNull(timeoutMs.milliseconds) {
            connectionState.first { s ->
                s == ConnectionState.Connected || s == ConnectionState.Unavailable
            }
        }
        val connected = state == ConnectionState.Connected
        Logger.i(
            LOG_TAG,
            "connectAndAwait connected=$connected state=${state?.name ?: "timeout"} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return connected
    }

    fun disconnect() {
        cancelIdleDisconnect()
        deathRecipient?.let { dr ->
            binder?.let { b ->
                try {
                    b.unlinkToDeath(dr, 0)
                } catch (_: Exception) {
                }
            }
        }
        deathRecipient = null
        if (bound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            bound = false
        }
        service = null
        binder = null
        storeService = null
        retryCount = 0
        _connectionState.value = ConnectionState.Disconnected
    }

    // --- AssistantTextSource ---

    override fun submit(query: String): Flow<AssistantFrame> = flow {
        activeRequests.incrementAndGet()
        cancelIdleDisconnect()
        try {
            val connected = ensureConnected()
            val svc = service
            if (!connected || svc == null) {
                throw ServiceUnavailableException()
            }
            val frameChannel = Channel<AssistantFrame>(Channel.CONFLATED)
            val callback = object : IRenderFrameCallback.Stub() {
                override fun onFrame(frame: RenderFrame?) {
                    if (frame != null) {
                        frameChannel.trySend(frame.toAssistantFrame())
                        if (frame.isFinal) {
                            frameChannel.close()
                        }
                    }
                }
            }
            coroutineScope {
                val disconnectWatcher = launch {
                    connectionState.first { state ->
                        state == ConnectionState.Disconnected ||
                                state == ConnectionState.Reconnecting ||
                                state == ConnectionState.Unavailable
                    }
                    frameChannel.close(ServiceUnavailableException())
                }
                try {
                    svc.submit(query, callback)
                    for (frame in frameChannel) {
                        emit(frame)
                    }
                } finally {
                    disconnectWatcher.cancel()
                    frameChannel.close()
                }
            }
        } finally {
            if (activeRequests.decrementAndGet() <= 0) {
                activeRequests.set(0)
                scheduleIdleDisconnect()
            }
        }
    }

    override suspend fun cancel() {
        try {
            service?.cancel()
        } catch (_: Exception) {
        }
    }

    override suspend fun resetConversation() {
        try {
            service?.resetConversation()
        } catch (_: Exception) {
        }
    }

    override fun readStore(storeId: String): String? {
        cancelIdleDisconnect()
        return try {
            val svc = storeService ?: return null
            val json = svc.readStore(storeId)
            Logger.d(
                LOG_TAG,
                "readStore storeId=$storeId result=${json != null} " +
                        "jsonLength=${json?.length ?: 0}"
            )
            json
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "readStore storeId=$storeId error=DeadObject")
            null
        } catch (_: RemoteException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "readStore storeId=$storeId error=RemoteException")
            null
        } finally {
            scheduleIdleDisconnect()
        }
    }

    override fun writeStore(storeId: String, json: String): Boolean {
        val svc = storeService ?: return false
        return try {
            svc.writeStore(storeId, json)
            Logger.i(LOG_TAG, "writeStore storeId=$storeId jsonLength=${json.length}")
            true
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "writeStore storeId=$storeId error=DeadObject")
            false
        } catch (_: RemoteException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "writeStore storeId=$storeId error=RemoteException")
            false
        }
    }

    override fun mutateStore(storeId: String, path: String, valueJson: String): String? {
        val svc = storeService ?: return null
        return try {
            val updatedJson = svc.mutateStore(storeId, path, valueJson)
            Logger.i(
                LOG_TAG,
                "mutateStore storeId=$storeId path=$path result=${updatedJson != null}"
            )
            updatedJson
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "mutateStore storeId=$storeId path=$path error=DeadObject")
            null
        } catch (_: RemoteException) {
            onBinderUnreachable()
            Logger.w(LOG_TAG, "mutateStore storeId=$storeId path=$path error=RemoteException")
            null
        }
    }

    override fun postNotification(title: String, content: String, uri: String?): Boolean {
        val svc = storeService ?: return false
        return try {
            svc.postNotification(title, content, uri)
            true
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            false
        } catch (_: RemoteException) {
            onBinderUnreachable()
            false
        }
    }

    // --- Binder death ---

    private fun onBinderUnreachable() {
        Logger.w(LOG_TAG, "binder unreachable, unbinding")
        service = null
        storeService = null
        binder = null
        mainHandler.post {
            cancelIdleDisconnect()
            deathRecipient = null
            if (bound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (_: Exception) {
                }
                bound = false
            }
            if (activeRequests.get() > 0) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun handleBinderDeath() {
        Logger.w(LOG_TAG, "binder died, unbinding")
        mainHandler.post {
            cancelIdleDisconnect()
            deathRecipient = null
            service = null
            storeService = null
            binder = null
            if (bound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (_: Exception) {
                }
                bound = false
            }
            if (activeRequests.get() > 0) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    // --- Service connection ---

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = binder?.let { IAgentRuntimeService.Stub.asInterface(it) }
            service = svc
            storeService = svc?.getStoreBinder()?.let { IAgentStoreService.Stub.asInterface(it) }
            this@AgentRuntimeClient.binder = binder

            deathRecipient?.let { dr ->
                binder?.let { b ->
                    try {
                        b.linkToDeath(dr, 0)
                    } catch (_: Exception) {
                    }
                }
            }

            retryCount = 0
            val connected = svc != null && storeService != null
            Logger.i(
                LOG_TAG,
                "onServiceConnected connected=$connected runtime=${svc != null} " +
                        "store=${storeService != null}"
            )
            _connectionState.value = if (connected) {
                ConnectionState.Connected
            } else {
                ConnectionState.Unavailable
            }
            if (connected) {
                scheduleIdleDisconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.w(LOG_TAG, "onServiceDisconnected")
            cancelIdleDisconnect()
            service = null
            storeService = null
            binder = null
            bound = false
            if (activeRequests.get() > 0) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun scheduleReconnect() {
        retryCount++
        if (retryCount > MAX_RETRIES) {
            Logger.w(LOG_TAG, "reconnect exhausted retry=$retryCount max=$MAX_RETRIES")
            _connectionState.value = ConnectionState.Unavailable
            return
        }
        Logger.d(
            LOG_TAG,
            "scheduleReconnect retry=$retryCount/$MAX_RETRIES delayMs=$RETRY_DELAY_MS"
        )
        _connectionState.value = ConnectionState.Reconnecting
        mainHandler.postDelayed(
            {
                if (_connectionState.value == ConnectionState.Reconnecting) {
                    doReconnect()
                }
            },
            RETRY_DELAY_MS,
        )
    }

    private fun doReconnect() {
        Logger.d(LOG_TAG, "doReconnect retry=$retryCount/$MAX_RETRIES")
        deathRecipient = IBinder.DeathRecipient { handleBinderDeath() }
        _connectionState.value = ConnectionState.Connecting

        val intent = Intent(BIND_ACTION).apply {
            setClassName(BuildConfig.APPLICATION_ID, SERVICE_CLASS)
        }

        try {
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            bound = false
            Logger.w(LOG_TAG, "doReconnect bind failed error=${e.message}")
        }
        if (!bound) {
            Logger.w(LOG_TAG, "doReconnect bind rejected")
            _connectionState.value = ConnectionState.Unavailable
        }
    }
}
