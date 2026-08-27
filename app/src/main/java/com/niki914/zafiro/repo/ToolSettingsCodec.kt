package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.enabledForAgent
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.niki914.zafiro.settings.model.RuntimeCustomTool as CustomTool

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

    fun parseCustomTools(json: String, agentId: String = MAIN_AGENT_ID): List<CustomTool> {
        return parseObject(json)
            .array(TOOLS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name = obj.string(NAME_KEY).trim()
                val command = obj.string(COMMAND_KEY).trim()
                if (name.isBlank() || command.isBlank()) return@mapNotNull null
                CustomTool(
                    name = name,
                    description = obj.string(DESCRIPTION_KEY).trim(),
                    command = command,
                    enabled = enabledForAgent(obj[ENABLED_FOR_AGENTS_KEY], agentId) == true,
                )
            }
    }

    fun encodeCustomTools(tools: List<CustomTool>, agentId: String = MAIN_AGENT_ID): String {
        return JsonObject(
            mapOf(
                TOOLS_KEY to JsonArray(
                    tools.map { tool ->
                        JsonObject(
                            mapOf(
                                NAME_KEY to JsonPrimitive(tool.name),
                                DESCRIPTION_KEY to JsonPrimitive(tool.description),
                                COMMAND_KEY to JsonPrimitive(tool.command),
                                ENABLED_FOR_AGENTS_KEY to JsonArray(
                                    if (tool.enabled) listOf(JsonPrimitive(agentId)) else emptyList()
                                ),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    private const val MAIN_AGENT_ID = "main"
    private const val ENABLED_FOR_AGENTS_KEY = "enabled_for_agents"
    private const val VERSION_KEY = "version"
    private const val BUILTIN_VERSION = 2
    private const val ENABLED_KEY = "enabled"
    private const val TOOLS_KEY = "tools"
    private const val NAME_KEY = "name"
    private const val DESCRIPTION_KEY = "description"
    private const val COMMAND_KEY = "command"
}
