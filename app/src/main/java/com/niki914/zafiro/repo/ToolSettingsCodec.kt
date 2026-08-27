package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import com.niki914.zafiro.settings.model.RuntimePyTool as PyTool

internal object ToolSettingsCodec {
    // v2：一个工具一个布尔，缺失回退 defaultEnabled；未知工具名由调用方按 registry 过滤（读端忽略、写端 GC）。
    // version 仅审计，不参与解析。旧 enabled_for_agents 键不识别 = 空配置，无迁移。
    fun parseBuiltinEnabled(json: String): Map<String, Boolean> {
        val enabled = parseObject(json)[ENABLED_KEY] as? JsonObject ?: return emptyMap()
        return enabled.mapNotNull { (toolName, value) ->
            (value as? JsonPrimitive)?.booleanOrNull?.let { toolName to it }
        }.toMap()
    }

    fun encodeBuiltinEnabled(enabled: Map<String, Boolean>): String {
        val flags = enabled.mapValues { (_, isEnabled) -> JsonPrimitive(isEnabled) }
        return JsonObject(
            mapOf(
                VERSION_KEY to JsonPrimitive(BUILTIN_VERSION),
                ENABLED_KEY to JsonObject(flags),
            )
        ).toString()
    }

    fun parsePyTools(json: String): List<PyTool> {
        return parseObject(json)
            .array(TOOLS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name = obj.string(NAME_KEY).trim()
                val code = obj.string(CODE_KEY)
                if (name.isBlank() || code.isBlank()) return@mapNotNull null
                PyTool(
                    name = name,
                    code = code,
                    description = obj.string(DESCRIPTION_KEY),
                    schemaJson = obj.string(SCHEMA_KEY),
                    enabled = (obj[ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull ?: true,
                    timeoutMs = (obj[TIMEOUT_KEY] as? JsonPrimitive)?.longOrNull
                        ?: PyTool.DEFAULT_PY_TOOL_TIMEOUT_MS,
                )
            }
    }

    fun encodePyTools(tools: List<PyTool>): String {
        return JsonObject(
            mapOf(
                TOOLS_KEY to JsonArray(
                    tools.map { tool ->
                        JsonObject(
                            mapOf(
                                NAME_KEY to JsonPrimitive(tool.name),
                                CODE_KEY to JsonPrimitive(tool.code),
                                DESCRIPTION_KEY to JsonPrimitive(tool.description),
                                SCHEMA_KEY to JsonPrimitive(tool.schemaJson),
                                ENABLED_KEY to JsonPrimitive(tool.enabled),
                                TIMEOUT_KEY to JsonPrimitive(tool.timeoutMs),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    private const val VERSION_KEY = "version"
    private const val BUILTIN_VERSION = 2
    private const val ENABLED_KEY = "enabled"
    private const val TOOLS_KEY = "tools"
    private const val NAME_KEY = "name"
    private const val DESCRIPTION_KEY = "description"
    private const val CODE_KEY = "code"
    private const val SCHEMA_KEY = "schema"
    private const val TIMEOUT_KEY = "timeout_ms"
}
