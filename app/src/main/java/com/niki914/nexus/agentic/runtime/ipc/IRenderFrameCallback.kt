package com.niki914.nexus.agentic.runtime.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

interface IRenderFrameCallback : IInterface {
    fun onFrame(frame: RenderFrame?)

    abstract class Stub : Binder(), IRenderFrameCallback {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_onFrame -> {
                    data.enforceInterface(DESCRIPTOR)
                    val frame = if (data.readInt() != 0) {
                        RenderFrame.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    onFrame(frame)
                    return true
                }

                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val DESCRIPTOR =
                "com.niki914.nexus.agentic.runtime.ipc.IRenderFrameCallback"
            private const val TRANSACTION_onFrame = 1

            fun asInterface(obj: IBinder?): IRenderFrameCallback? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin != null && iin is IRenderFrameCallback) return iin
                return Proxy(obj)
            }
        }

        private class Proxy(private val remote: IBinder) : IRenderFrameCallback {
            override fun asBinder(): IBinder = remote

            override fun onFrame(frame: RenderFrame?) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    if (frame != null) {
                        data.writeInt(1)
                        frame.writeToParcel(data, 0)
                    } else {
                        data.writeInt(0)
                    }
                    remote.transact(TRANSACTION_onFrame, data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            }
        }
    }
}
