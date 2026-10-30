package com.niki914.zafiro.repo

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

    fun JsonObject.long(key: String, default: Long): Long {
        return (this[key] as? JsonPrimitive)?.longOrNull ?: default
    }

    fun JsonObject.int(key: String, default: Int): Int {
        return (this[key] as? JsonPrimitive)?.intOrNull ?: default
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

    fun JsonObject.stringNonBlankOrNull(key: String): String? {
        return string(key).trim().takeIf(String::isNotBlank)
    }

    inline fun <reified E : Enum<E>> JsonObject.enumOrDefault(key: String, default: E): E {
        val raw = string(key).trim()
        if (raw.isEmpty()) return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    fun JsonObject.intClamped(key: String, default: Int, min: Int, max: Int): Int {
        return int(key, default).coerceIn(min, max)
    }

    fun JsonObject.longClamped(key: String, default: Long, min: Long, max: Long): Long {
        return long(key, default).coerceIn(min, max)
    }

    inline fun <T> JsonObject.sanitized(key: String, sanitize: (String) -> T): T {
        return sanitize(string(key))
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
