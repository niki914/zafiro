package com.niki914.nexus.agentic.repo

import android.content.Context
import com.niki914.nexus.ipc.IpcReadResult
import com.niki914.nexus.ipc.IpcWriteResult
import com.niki914.nexus.ipc.XIpcBridge
import com.niki914.nexus.ipc.store.StoreDescriptorRegistry

internal interface DomainSettingsStore {
    suspend fun readJson(context: Context, storeId: String): String

    suspend fun writeJsonFromOwner(context: Context, storeId: String, json: String): Boolean

    suspend fun mutateJson(context: Context, storeId: String, path: String, value: Any?): Boolean
}

internal object XIpcDomainSettingsStore : DomainSettingsStore {

    @Volatile
    var client: XIpcBridge.StoreClient? = null

    override suspend fun readJson(context: Context, storeId: String): String {
        val defaultJson = StoreDescriptorRegistry.resolveDynamic(storeId)?.defaultJson ?: EMPTY_JSON
        return when (val result = XIpcBridge.readStoreJson(context, storeId, client)) {
            is IpcReadResult.Success -> result.json
            IpcReadResult.NotFound,
            IpcReadResult.Unreachable -> defaultJson
        }
    }

    override suspend fun writeJsonFromOwner(
        context: Context,
        storeId: String,
        json: String,
    ): Boolean {
        return XIpcBridge.writeStoreJsonFromOwner(context, storeId, json, client) is IpcWriteResult.Success
    }

    override suspend fun mutateJson(
        context: Context,
        storeId: String,
        path: String,
        value: Any?,
    ): Boolean {
        return XIpcBridge.mutateStoreJson(context, storeId, path, value, client).writeResult is IpcWriteResult.Success
    }

    private const val EMPTY_JSON = "{}"
}
