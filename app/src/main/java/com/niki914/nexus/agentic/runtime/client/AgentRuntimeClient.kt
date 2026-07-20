package com.niki914.nexus.agentic.runtime.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService
import com.niki914.nexus.agentic.runtime.ipc.IAgentStoreService
import com.niki914.nexus.agentic.runtime.ipc.IRenderFrameCallback
import com.niki914.nexus.agentic.runtime.ipc.RenderFrame
import com.niki914.nexus.ipc.XIpcBridge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class ServiceUnavailableException :
    IllegalStateException("Agent runtime service is not connected")

class AgentRuntimeClient(private val context: Context) : AssistantTextSource, XIpcBridge.StoreClient {

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

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val NEXUS_PACKAGE = "com.niki914.nexus.agentic"
        private const val BIND_ACTION = "com.niki914.nexus.agentic.runtime.BIND"
        private const val SERVICE_CLASS = "com.niki914.nexus.agentic.runtime.service.AgentRuntimeService"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    // --- Connection ---

    fun connect(): Boolean {
        retryCount = 0
        _connectionState.value = ConnectionState.Connecting

        deathRecipient = IBinder.DeathRecipient { handleBinderDeath() }

        val intent = Intent(BIND_ACTION).apply {
            setClassName(NEXUS_PACKAGE, SERVICE_CLASS)
        }

        val result = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Unavailable
            return false
        }

        if (result) {
            bound = true
        } else {
            _connectionState.value = ConnectionState.Unavailable
        }
        return result
    }

    suspend fun connectAndAwait(timeoutMs: Long = 5_000): Boolean {
        connect()
        val state = withTimeoutOrNull(timeoutMs.milliseconds) {
            connectionState.first { s ->
                s == ConnectionState.Connected || s == ConnectionState.Unavailable
            }
        }
        return state == ConnectionState.Connected
    }

    fun disconnect() {
        deathRecipient?.let { dr ->
            binder?.let { b ->
                try { b.unlinkToDeath(dr, 0) } catch (_: Exception) {}
            }
        }
        deathRecipient = null
        if (bound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            bound = false
        }
        service = null
        binder = null
        storeService = null
        retryCount = 0
        _connectionState.value = ConnectionState.Disconnected
    }

    // --- AssistantTextSource ---

    override fun submit(query: String): Flow<RenderFrame> = callbackFlow {
        val svc = service
        if (svc == null) {
            close(ServiceUnavailableException())
            return@callbackFlow
        }
        val callback = object : IRenderFrameCallback.Stub() {
            override fun onFrame(frame: RenderFrame?) {
                if (frame != null) {
                    trySend(frame)
                    if (frame.isFinal) {
                        close()
                    }
                }
            }
        }
        try {
            svc.submit(query, callback)
        } catch (e: Exception) {
            close(e)
        }
        awaitClose {}
    }

    override suspend fun cancel() {
        try { service?.cancel() } catch (_: Exception) {}
    }

    override suspend fun resetConversation() {
        try { service?.resetConversation() } catch (_: Exception) {}
    }

    override fun readStore(storeId: String): String? {
        val svc = storeService ?: return null
        return try {
            svc.readStore(storeId)
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            null
        } catch (_: RemoteException) {
            onBinderUnreachable()
            null
        }
    }

    override fun writeStore(storeId: String, json: String): Boolean {
        val svc = storeService ?: return false
        return try {
            svc.writeStore(storeId, json)
            true
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            false
        } catch (_: RemoteException) {
            onBinderUnreachable()
            false
        }
    }

    override fun mutateStore(storeId: String, path: String, valueJson: String): String? {
        val svc = storeService ?: return null
        return try {
            svc.mutateStore(storeId, path, valueJson)
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            null
        } catch (_: RemoteException) {
            onBinderUnreachable()
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

    override fun postNetworkErrorNotification(): Boolean {
        val svc = storeService ?: return false
        return try {
            svc.postNetworkErrorNotification()
            true
        } catch (_: DeadObjectException) {
            onBinderUnreachable()
            false
        } catch (_: RemoteException) {
            onBinderUnreachable()
            false
        }
    }

    override fun postUnsupportedVersionNotification(hostPackageName: String?, hostVersion: String?): Boolean {
        val svc = storeService ?: return false
        return try {
            svc.postUnsupportedVersionNotification(hostPackageName, hostVersion)
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
        service = null
        storeService = null
        binder = null
        mainHandler.post {
            deathRecipient = null
            if (bound) {
                try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                bound = false
            }
            scheduleReconnect()
        }
    }

    private fun handleBinderDeath() {
        mainHandler.post {
            deathRecipient = null
            service = null
            storeService = null
            binder = null
            if (bound) {
                try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                bound = false
            }
            scheduleReconnect()
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
                    try { b.linkToDeath(dr, 0) } catch (_: Exception) {}
                }
            }

            retryCount = 0
            _connectionState.value = if (svc != null && storeService != null) {
                ConnectionState.Connected
            } else {
                ConnectionState.Unavailable
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            storeService = null
            binder = null
            bound = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        retryCount++
        if (retryCount > MAX_RETRIES) {
            _connectionState.value = ConnectionState.Unavailable
            return
        }
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
        deathRecipient = IBinder.DeathRecipient { handleBinderDeath() }
        _connectionState.value = ConnectionState.Connecting

        val intent = Intent(BIND_ACTION).apply {
            setClassName(NEXUS_PACKAGE, SERVICE_CLASS)
        }

        try {
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            bound = false
        }
        if (!bound) {
            _connectionState.value = ConnectionState.Unavailable
        }
    }
}
