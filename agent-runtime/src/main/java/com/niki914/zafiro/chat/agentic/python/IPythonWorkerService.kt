package com.niki914.zafiro.chat.agentic.python

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Python exec 的结构化结果。Binder 返回值不再承载两种语义（路径 vs 内容）：
 * - [Status.OK]：输出已写入 [filePath]（worker 自算 cacheDir，无需入参传递）
 * - [Status.TIMEOUT]：超时，[filePath] 指向已产出的部分输出文件
 * - [Status.EXEC_ERROR]：worker 侧异常文本，走 [inlineText]（历史遗留字符串路径）
 * - 写盘失败（极罕见）：[Status.OK] + [inlineText] 装尾部 1MB clip（谁裁剪谁负责，Java 不感知）
 */
data class PyExecResult(
    val status: Status,
    val filePath: String?,
    val inlineText: String?,
) {
    enum class Status { OK, TIMEOUT, EXEC_ERROR }

    companion object {
        const val INLINE_CLIP_BYTES = 1024 * 1024

        fun fromParcel(reply: Parcel): PyExecResult {
            val status = Status.entries[reply.readInt()]
            return PyExecResult(
                status = status,
                filePath = if (reply.readInt() != 0) reply.readString() else null,
                inlineText = if (reply.readInt() != 0) reply.readString() else null,
            )
        }
    }

    fun writeToParcel(reply: Parcel) {
        reply.writeInt(status.ordinal)
        if (filePath != null) {
            reply.writeInt(1)
            reply.writeString(filePath)
        } else {
            reply.writeInt(0)
        }
        if (inlineText != null) {
            reply.writeInt(1)
            reply.writeString(inlineText)
        } else {
            reply.writeInt(0)
        }
    }
}

/**
 * Binder interface to the Python worker running in the dedicated `:python` process.
 *
 * All three methods are synchronous (blocking) calls:
 * - [exec] blocks until the Python code finishes or its own join timeout fires
 *   (returns [PyExecResult.Status.TIMEOUT] with a partial-output file). If the
 *   interpreter is hard-stuck (native code holding the GIL), the call may never
 *   return — the client must wrap it in a timeout and then call [kill].
 * - [ping] performs a fast interpreter probe; it also never returns when the
 *   interpreter is stuck. The client uses it to detect a dead interpreter
 *   before paying a full exec timeout.
 * - [kill] destroys the worker process without touching the interpreter
 *   (always executable while a Binder thread is free).
 */
interface IPythonWorkerService : IInterface {
    fun exec(code: String?, timeoutMs: Long): PyExecResult
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
                    result.writeToParcel(reply!!)
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

            override fun exec(code: String?, timeoutMs: Long): PyExecResult {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(code)
                    data.writeLong(timeoutMs)
                    remote.transact(TRANSACTION_exec, data, reply, 0)
                    reply.readException()
                    return PyExecResult.fromParcel(reply)
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
