package com.niki914.okia.tooling

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 工具 JSON Schema 清洗器：在将 Schema 传递给模型提供商（如 OpenAI / Anthropic）前，
 * 剔除或修整可能导致模型端解析器拒绝的非法或极端语法约束。
 *
 * 典型场景：
 * 某些 MCP 服务端（如 REA）参数中包含带有 NUL 字符转义的正则 pattern（例如 `^[^\0]*$`），
 * OpenAI API 的正则校验引擎会报错 `"... is not a 'regex'"` 并以 HTTP 400 拒绝整个请求。
 */
internal object ToolSchemaSanitizer {

    fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (key == "pattern" && value is JsonPrimitive && value.isString) {
                    val pattern = value.content
                    if (isSafePattern(pattern)) {
                        put(key, value)
                    }
                } else {
                    put(key, sanitize(value))
                }
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { add(sanitize(it)) }
        }

        else -> element
    }

    private fun isSafePattern(pattern: String): Boolean {
        // 包含 NUL 字节或被转义的 NUL (\0) 会被多数模型端（如 OpenAI）拒绝
        if (pattern.contains("\u0000") || pattern.contains("\\0")) return false
        return try {
            Regex(pattern)
            true
        } catch (_: Exception) {
            false
        }
    }
}
