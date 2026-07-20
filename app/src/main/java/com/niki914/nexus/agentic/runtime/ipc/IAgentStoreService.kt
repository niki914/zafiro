package com.niki914.nexus.agentic.runtime.ipc

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

interface IAgentStoreService : IInterface {
    fun readStore(storeId: String?): String?
    fun writeStore(storeId: String?, json: String?)
    fun mutateStore(storeId: String?, path: String?, valueJson: String?): String?
    fun postNotification(title: String?, content: String?, uri: String?)
    fun postNetworkErrorNotification()
    fun postUnsupportedVersionNotification(hostPackageName: String?, hostVersion: String?)

    abstract class Stub : Binder(), IAgentStoreService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_readStore -> {
                    data.enforceInterface(DESCRIPTOR)
                    val storeId = data.readString()
                    val result = readStore(storeId)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }
                TRANSACTION_writeStore -> {
                    data.enforceInterface(DESCRIPTOR)
                    val storeId = data.readString()
                    val json = data.readString()
                    writeStore(storeId, json)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_mutateStore -> {
                    data.enforceInterface(DESCRIPTOR)
                    val storeId = data.readString()
                    val path = data.readString()
                    val valueJson = data.readString()
                    val result = mutateStore(storeId, path, valueJson)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }
                TRANSACTION_postNotification -> {
                    data.enforceInterface(DESCRIPTOR)
                    val title = data.readString()
                    val content = data.readString()
                    val uri = data.readString()
                    postNotification(title, content, uri)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_postNetworkErrorNotification -> {
                    data.enforceInterface(DESCRIPTOR)
                    postNetworkErrorNotification()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_postUnsupportedVersionNotification -> {
                    data.enforceInterface(DESCRIPTOR)
                    val hostPackageName = data.readString()
                    val hostVersion = data.readString()
                    postUnsupportedVersionNotification(hostPackageName, hostVersion)
                    reply?.writeNoException()
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val DESCRIPTOR = "com.niki914.nexus.agentic.runtime.ipc.IAgentStoreService"
            private const val TRANSACTION_readStore = 1
            private const val TRANSACTION_writeStore = 2
            private const val TRANSACTION_mutateStore = 3
            private const val TRANSACTION_postNotification = 4
            private const val TRANSACTION_postNetworkErrorNotification = 5
            private const val TRANSACTION_postUnsupportedVersionNotification = 6

            fun asInterface(obj: IBinder?): IAgentStoreService? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                if (iin != null && iin is IAgentStoreService) return iin
                return Proxy(obj)
            }
        }

        private class Proxy(private val remote: IBinder) : IAgentStoreService {
            override fun asBinder(): IBinder = remote

            override fun readStore(storeId: String?): String? {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(storeId)
                    remote.transact(TRANSACTION_readStore, data, reply, 0)
                    reply.readException()
                    return reply.readString()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun writeStore(storeId: String?, json: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(storeId)
                    data.writeString(json)
                    remote.transact(TRANSACTION_writeStore, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun mutateStore(storeId: String?, path: String?, valueJson: String?): String? {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(storeId)
                    data.writeString(path)
                    data.writeString(valueJson)
                    remote.transact(TRANSACTION_mutateStore, data, reply, 0)
                    reply.readException()
                    return reply.readString()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun postNotification(title: String?, content: String?, uri: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(title)
                    data.writeString(content)
                    data.writeString(uri)
                    remote.transact(TRANSACTION_postNotification, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun postNetworkErrorNotification() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_postNetworkErrorNotification, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun postUnsupportedVersionNotification(hostPackageName: String?, hostVersion: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(hostPackageName)
                    data.writeString(hostVersion)
                    remote.transact(TRANSACTION_postUnsupportedVersionNotification, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
