package com.niki914.nexus.agentic.repo

import android.content.Context
import com.niki914.nexus.store.StoreDescriptorRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class FakeDomainSettingsStore(
    vararg initialJson: Pair<String, String>,
    private val ownerWriteSucceeds: Boolean = true,
) : DomainSettingsStore {
    private val jsonByStoreId = initialJson.toMap().toMutableMap()
    val readIds = mutableListOf<String>()
    val writeIds = mutableListOf<String>()
    val mutateCalls = mutableListOf<Pair<String, String>>()

    val writeCount: Int
        get() = writeIds.size

    override suspend fun readJson(context: Context, storeId: String): String {
        readIds += storeId
        return jsonByStoreId[storeId]
            ?: StoreDescriptorRegistry.resolveDynamic(storeId)?.defaultJson
            ?: "{}"
    }

    override suspend fun writeJsonFromOwner(context: Context, storeId: String, json: String): Boolean {
        if (!ownerWriteSucceeds) {
            return false
        }
        writeIds += storeId
        jsonByStoreId[storeId] = json
        return true
    }

    override suspend fun mutateJson(context: Context, storeId: String, path: String, value: Any?): Boolean {
        mutateCalls += storeId to path
        val current = Json.parseToJsonElement(jsonByStoreId[storeId] ?: "{}").jsonObject.toMutableMap()
        current[path] = value.toJsonElement()
        jsonByStoreId[storeId] = JsonObject(current).toString()
        return true
    }

    fun jsonFor(storeId: String): String {
        return jsonByStoreId[storeId] ?: error("Missing json for $storeId")
    }

    private fun Any?.toJsonElement(): JsonElement {
        return when (this) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(this)
            is Int -> JsonPrimitive(this)
            is Long -> JsonPrimitive(this)
            is Float -> JsonPrimitive(this)
            is Double -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Map<*, *> -> JsonObject(mapNotNull { (key, value) ->
                key?.toString()?.let { it to value.toJsonElement() }
            }.toMap())
            is Iterable<*> -> JsonArray(map { item -> item.toJsonElement() })
            is Array<*> -> JsonArray(map { item -> item.toJsonElement() })
            else -> JsonPrimitive(toString())
        }
    }
}
