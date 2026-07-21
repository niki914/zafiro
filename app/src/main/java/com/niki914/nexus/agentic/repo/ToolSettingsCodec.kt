package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.array
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.enabledForAgent
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.obj
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool

internal object ToolSettingsCodec {
    fun parseBuiltinEnabledForAgents(
        json: String,
        agentId: String = MAIN_AGENT_ID
    ): Map<String, Boolean> {
        return parseObject(json)
            .obj(ENABLED_FOR_AGENTS_KEY)
            ?.mapNotNull { (toolName, agents) ->
                enabledForAgent(agents, agentId)?.let { enabled -> toolName to enabled }
            }
            ?.toMap()
            ?: emptyMap()
    }

    fun encodeBuiltinEnabledForAgents(
        enabled: Map<String, Boolean>,
        agentId: String = MAIN_AGENT_ID,
    ): String {
        val flags = enabled.mapValues { (_, isEnabled) ->
            JsonArray(if (isEnabled) listOf(JsonPrimitive(agentId)) else emptyList())
        }
        return JsonObject(mapOf(ENABLED_FOR_AGENTS_KEY to JsonObject(flags))).toString()
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
    private const val TOOLS_KEY = "tools"
    private const val NAME_KEY = "name"
    private const val DESCRIPTION_KEY = "description"
    private const val COMMAND_KEY = "command"
}
