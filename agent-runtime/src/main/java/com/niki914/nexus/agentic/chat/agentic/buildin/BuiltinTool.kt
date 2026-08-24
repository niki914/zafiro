package com.niki914.nexus.agentic.chat.agentic.buildin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

abstract class BuiltinTool {
    abstract val name: String

    open val description: String
        get() = "Builtin tool: $name"

    open val defaultEnabled: Boolean = false

    // JSON Schema（type:object + properties + required），随请求序列化为 parameters。
    // null = 无参数约束（请求体省略 parameters，模型按 description 调用）。
    // T2a 迁移：kai LocalToolConfig DSL 已删除；schema 声明式常量为唯一来源。
    open val inputSchemaJson: String? = null

    abstract suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult
}

interface RawJsonBuiltinTool {
    suspend fun invokeRawJson(request: BuiltinToolRequest): String
}

data class BuiltinToolRequest(
    val name: String,
    val argumentsJson: String,
)

data class BuiltinToolResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val hint: String,
    val fieldErrors: Map<String, String>,
    val data: JsonObject,
) {
    fun toJsonString(): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(ok),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
                "hint" to JsonPrimitive(hint),
                "field_errors" to JsonObject(
                    fieldErrors.mapValues { (_, value) -> JsonPrimitive(value) }
                ),
                "data" to data,
            )
        ).toString()
    }

    companion object {
        fun success(
            message: String,
            data: JsonObject = JsonObject(emptyMap()),
            hint: String = "",
        ): BuiltinToolResult {
            return BuiltinToolResult(
                ok = true,
                code = "OK",
                message = message,
                hint = hint,
                fieldErrors = emptyMap(),
                data = data,
            )
        }

        fun failure(
            code: String,
            message: String,
            hint: String = "",
            fieldErrors: Map<String, String> = emptyMap(),
            data: JsonObject = JsonObject(emptyMap()),
        ): BuiltinToolResult {
            return BuiltinToolResult(
                ok = false,
                code = code,
                message = message,
                hint = hint,
                fieldErrors = fieldErrors,
                data = data,
            )
        }
    }
}
