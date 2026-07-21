package com.niki914.nexus.store

import com.niki914.nexus.xposed.api.util.xTry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object IpcJsonMutator {

    fun mutate(
        json: String,
        path: String,
        valueJson: String
    ): String {
        val root = parseJsonObject(json)
        val segments = path
            .split(".")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        require(segments.isNotEmpty()) { "key must not be blank" }

        var current = root
        segments.dropLast(1).forEach { segment ->
            val next = current.optJSONObject(segment) ?: JSONObject().also {
                current.put(segment, it)
            }
            current = next
        }
        current.put(segments.last(), toJsonValue(parseValueJson(valueJson)))
        return root.toString()
    }

    private fun parseJsonObject(json: String): JSONObject {
        return xTry("IpcJsonMutator.parseJsonObject") {
            if (json.isBlank()) JSONObject() else JSONObject(json)
        } ?: JSONObject()
    }

    private fun parseValueJson(valueJson: String): Any? {
        return xTry("IpcJsonMutator.parseValueJson") {
            val tokener = JSONTokener(valueJson)
            val value = tokener.nextValue()
            check(tokener.nextClean() == 0.toChar())
            value
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is Boolean, is Number, is String -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        put(key.toString(), toJsonValue(nestedValue))
                    }
                }
            }

            is Iterable<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }

            is Array<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }

            else -> value.toString()
        }
    }
}
