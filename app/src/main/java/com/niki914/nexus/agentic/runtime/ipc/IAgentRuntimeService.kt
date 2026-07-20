package com.niki914.nexus.agentic.runtime.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

interface IAgentRuntimeService : IInterface {
    fun submit(query: String?, callback: IRenderFrameCallback?)
    fun cancel()
    fun resetConversation()

    abstract class Stub : Binder(), IAgentRuntimeService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_submit -> {
                    data.enforceInterface(DESCRIPTOR)
                    val query = data.readString()
                    val callback = IRenderFrameCallback.Stub.asInterface(data.readStrongBinder())
                    submit(query, callback)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_cancel -> {
                    data.enforceInterface(DESCRIPTOR)
                    cancel()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_resetConversation -> {
                    data.enforceInterface(DESCRIPTOR)
                    resetConversation()
                    reply?.writeNoException()
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val DESCRIPTOR = "com.niki914.nexus.agentic.runtime.ipc.IAgentRuntimeService"
            private const val TRANSACTION_submit = 1
            private const val TRANSACTION_cancel = 2
            private const val TRANSACTION_resetConversation = 3

            fun asInterface(obj: IBinder?): IAgentRuntimeService? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin != null && iin is IAgentRuntimeService) return iin
                return Proxy(obj)
            }
        }

        private class Proxy(private val remote: IBinder) : IAgentRuntimeService {
            override fun asBinder(): IBinder = remote

            override fun submit(query: String?, callback: IRenderFrameCallback?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(query)
                    data.writeStrongBinder(callback?.asBinder())
                    remote.transact(TRANSACTION_submit, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun cancel() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_cancel, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun resetConversation() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_resetConversation, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
