package com.niki914.nexus.ipc.store

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object XIpcStoreRepository {

    private val storeMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun readJson(context: Context, storeId: String): String {
        val descriptor = StoreDescriptorRegistry.resolveDynamic(storeId) ?: return EMPTY_JSON
        return mutexFor(storeId).withLock {
            readPersistedJson(context, descriptor) ?: descriptor.defaultJson
        }
    }

    suspend fun writeJson(
        context: Context,
        storeId: String,
        json: String
    ): String {
        val descriptor = StoreDescriptorRegistry.require(storeId)
        return mutexFor(storeId).withLock {
            requireJsonObject(json)
            writePersistedJson(context, descriptor, json)
            json
        }
    }

    suspend fun mutateJson(
        context: Context,
        storeId: String,
        path: String,
        valueJson: String
    ): String {
        val descriptor = StoreDescriptorRegistry.require(storeId)
        return mutexFor(storeId).withLock {
            val updatedJson = IpcJsonMutator.mutate(
                json = readPersistedJson(context, descriptor) ?: descriptor.defaultJson,
                path = path,
                valueJson = valueJson
            )
            writePersistedJson(context, descriptor, updatedJson)
            updatedJson
        }
    }

    private fun mutexFor(storeId: String): Mutex {
        return storeMutexes.computeIfAbsent(storeId) { Mutex() }
    }

    private fun readPersistedJson(
        context: Context,
        descriptor: StoreDescriptor
    ): String? {
        val json = ConfigPersistence.readJson(context, descriptor)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (isJsonObject(json)) json else null
    }

    private fun writePersistedJson(
        context: Context,
        descriptor: StoreDescriptor,
        json: String
    ) {
        ConfigPersistence.writeJson(context, descriptor, json)
    }

    private fun requireJsonObject(json: String) {
        JSONObject(json)
    }

    private fun isJsonObject(json: String): Boolean {
        return runCatching {
            JSONObject(json)
            true
        }.getOrDefault(false)
    }

    private const val EMPTY_JSON = "{}"
}
