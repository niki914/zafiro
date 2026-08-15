package com.niki914.nexus.agentic.repo

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.nexus.store.IpcReadResult
import com.niki914.nexus.store.IpcWriteResult
import com.niki914.nexus.store.StoreDescriptorRegistry
import com.niki914.nexus.store.XIpcBridge

internal interface DomainSettingsStore {
    suspend fun readJson(context: Context, storeId: String): String

    suspend fun writeJsonFromOwner(context: Context, storeId: String, json: String): Boolean

    suspend fun mutateJson(context: Context, storeId: String, path: String, value: Any?): Boolean
}

internal class XIpcDomainSettingsStore(
    private val client: XIpcBridge.StoreClient?,
) : DomainSettingsStore {

    private companion object {
        private const val LOG_TAG = "niki914_nexus_XIpcDomainSettingsStore"
        private const val EMPTY_JSON = "{}"
    }

    override suspend fun readJson(context: Context, storeId: String): String {
        val startedAtMs = System.currentTimeMillis()
        val defaultJson = StoreDescriptorRegistry.resolveDynamic(storeId)?.defaultJson ?: EMPTY_JSON
        val json = when (val result = XIpcBridge.readStoreJson(context, storeId, client)) {
            is IpcReadResult.Success -> result.json
            IpcReadResult.NotFound,
            IpcReadResult.Unreachable -> defaultJson
        }
        Logger.d(
            LOG_TAG,
            "readJson storeId=$storeId jsonLength=${json.length} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return json
    }

    override suspend fun writeJsonFromOwner(
        context: Context,
        storeId: String,
        json: String,
    ): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = XIpcBridge.writeStoreJsonFromOwner(
            context,
            storeId,
            json,
            client
        ) is IpcWriteResult.Success
        Logger.i(
            LOG_TAG,
            "writeJsonFromOwner storeId=$storeId result=$result jsonLength=${json.length} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }

    override suspend fun mutateJson(
        context: Context,
        storeId: String,
        path: String,
        value: Any?,
    ): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = XIpcBridge.mutateStoreJson(
            context,
            storeId,
            path,
            value,
            client
        ).writeResult is IpcWriteResult.Success
        Logger.i(
            LOG_TAG,
            "mutateJson storeId=$storeId path=$path result=$result " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }
}
