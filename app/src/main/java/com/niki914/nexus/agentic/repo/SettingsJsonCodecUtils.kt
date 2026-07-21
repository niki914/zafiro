package com.niki914.nexus.agentic.repo

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SettingsJsonCodecUtils {
    fun parseObject(json: String): JsonObject {
        return try {
            Json.parseToJsonElement(json).jsonObject
        } catch (_: SerializationException) {
            JsonObject(emptyMap())
        } catch (_: IllegalArgumentException) {
            JsonObject(emptyMap())
        }
    }

    fun JsonObject.string(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        return (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    }

    fun JsonObject.obj(key: String): JsonObject? {
        return this[key] as? JsonObject
    }

    fun JsonObject.array(key: String): JsonArray? {
        return this[key] as? JsonArray
    }

    fun JsonArray?.orEmptyObjects(): List<JsonObject> {
        return this?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    fun JsonArray?.stringValues(): List<String> {
        return this
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter(String::isNotBlank)
            ?: emptyList()
    }

    fun stringArray(values: List<String>): JsonArray {
        return JsonArray(values.map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))
    }

    fun enabledForAgent(element: JsonElement?, agentId: String): Boolean? {
        val agents = element as? JsonArray ?: return null
        return agents.any { item ->
            (item as? JsonPrimitive)
                ?.takeIf { it.toString().startsWith("\"") }
                ?.contentOrNull == agentId
        }
    }
}
