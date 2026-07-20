package com.niki914.nexus.agentic.runtime.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.niki914.nexus.agentic.runtime.ipc.AgentEvent
import com.niki914.nexus.agentic.runtime.ipc.IAgentEventCallback
import com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

class AgentRuntimeClient(private val context: Context) {

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
    private var binder: IBinder? = null
    private var bound = false
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var retryCount = 0

    private val pendingSubmits = ConcurrentLinkedQueue<() -> Unit>()
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

    fun disconnect() {
        deathRecipient?.let { dr ->
            binder?.let { b ->
                try {
                    b.unlinkToDeath(dr, 0)
                } catch (_: Exception) {}
            }
        }
        deathRecipient = null
        if (bound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            bound = false
        }
        pendingSubmits.clear()
        service = null
        binder = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // --- IPC methods ---

    fun submit(
        query: String,
        params: Bundle = Bundle.EMPTY,
        onFrame: (text: String, isFirst: Boolean, isFinal: Boolean) -> Unit,
    ): Result<Unit> {
        val svc = service
        if (svc == null) {
            pendingSubmits.add {
                submit(query, params, onFrame)
            }
            return Result.success(Unit)
        }
        return try {
            val callback = object : IAgentEventCallback.Stub() {
                override fun onEvent(event: AgentEvent?) {
                    if (event != null) {
                        mainHandler.post { onFrame(event.text, event.isFirst, event.isFinal) }
                    }
                }
            }
            svc.submit(query, params, callback)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cancel() {
        try {
            service?.cancel()
        } catch (_: Exception) {}
    }

    fun resetConversation() {
        try {
            service?.resetConversation()
        } catch (_: Exception) {}
    }

    // --- Binder death ---

    private fun handleBinderDeath() {
        mainHandler.post {
            deathRecipient = null
            service = null
            binder = null
            pendingSubmits.clear()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // --- Service connection ---

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = binder?.let { IAgentRuntimeService.Stub.asInterface(it) }
            service = svc
            this@AgentRuntimeClient.binder = binder

            // Register death recipient
            deathRecipient?.let { dr ->
                binder?.let { b ->
                    try {
                        b.linkToDeath(dr, 0)
                    } catch (_: Exception) {}
                }
            }

            _connectionState.value = ConnectionState.Connected

            // Drain pending submits
            while (true) {
                val pending = pendingSubmits.poll() ?: break
                pending()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binder = null
            _connectionState.value = ConnectionState.Reconnecting

            retryCount++
            if (retryCount <= MAX_RETRIES) {
                mainHandler.postDelayed(
                    {
                        if (_connectionState.value == ConnectionState.Reconnecting) {
                            connect()
                        }
                    },
                    RETRY_DELAY_MS,
                )
            } else {
                _connectionState.value = ConnectionState.Unavailable
            }
        }
    }
}
