package com.niki914.nexus.agentic.runtime.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

interface IAgentEventCallback : IInterface {
    fun onEvent(event: AgentEvent?)

    abstract class Stub : Binder(), IAgentEventCallback {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_onEvent -> {
                    data.enforceInterface(DESCRIPTOR)
                    val event = if (data.readInt() != 0) {
                        AgentEvent.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    onEvent(event)
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val DESCRIPTOR = "com.niki914.nexus.agentic.runtime.ipc.IAgentEventCallback"
            private const val TRANSACTION_onEvent = 1

            fun asInterface(obj: IBinder?): IAgentEventCallback? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin != null && iin is IAgentEventCallback) return iin
                return Proxy(obj)
            }
        }

        private class Proxy(private val remote: IBinder) : IAgentEventCallback {
            override fun asBinder(): IBinder = remote

            override fun onEvent(event: AgentEvent?) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    if (event != null) {
                        data.writeInt(1)
                        event.writeToParcel(data, 0)
                    } else {
                        data.writeInt(0)
                    }
                    remote.transact(TRANSACTION_onEvent, data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            }
        }
    }
}
