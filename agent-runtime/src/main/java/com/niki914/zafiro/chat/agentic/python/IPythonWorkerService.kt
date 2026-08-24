package com.niki914.zafiro.chat.agentic.python

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Binder interface to the Python worker running in the dedicated `:python` process.
 *
 * All three methods are synchronous (blocking) calls:
 * - [exec] blocks until the Python code finishes or its own join timeout fires
 *   (returns the `TimeoutError` text). If the interpreter is hard-stuck (native
 *   code holding the GIL), the call may never return — the client must wrap it
 *   in a timeout and then call [kill].
 * - [ping] performs a fast interpreter probe; it also never returns when the
 *   interpreter is stuck. The client uses it to detect a dead interpreter
 *   before paying a full exec timeout.
 * - [kill] destroys the worker process without touching the interpreter
 *   (always executable while a Binder thread is free).
 */
interface IPythonWorkerService : IInterface {
    fun exec(code: String?, timeoutMs: Long): String?
    fun ping(): String?
    fun kill()

    abstract class Stub : Binder(), IPythonWorkerService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_exec -> {
                    data.enforceInterface(DESCRIPTOR)
                    val codeText = data.readString()
                    val timeoutMs = data.readLong()
                    val result = exec(codeText, timeoutMs)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }

                TRANSACTION_ping -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = ping()
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }

                TRANSACTION_kill -> {
                    data.enforceInterface(DESCRIPTOR)
                    kill()
                    reply?.writeNoException()
                    return true
                }

                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val DESCRIPTOR =
                "com.niki914.zafiro.chat.agentic.python.IPythonWorkerService"
            private const val TRANSACTION_exec = 1
            private const val TRANSACTION_ping = 2
            private const val TRANSACTION_kill = 3

            fun asInterface(obj: IBinder?): IPythonWorkerService? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin != null && iin is IPythonWorkerService) return iin
                return Proxy(obj)
            }
        }

        private class Proxy(private val remote: IBinder) : IPythonWorkerService {
            override fun asBinder(): IBinder = remote

            override fun exec(code: String?, timeoutMs: Long): String? {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(code)
                    data.writeLong(timeoutMs)
                    remote.transact(TRANSACTION_exec, data, reply, 0)
                    reply.readException()
                    return reply.readString()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun ping(): String? {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_ping, data, reply, 0)
                    reply.readException()
                    return reply.readString()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun kill() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_kill, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
