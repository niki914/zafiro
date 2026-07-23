package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.McpCachedTool
import com.niki914.nexus.agentic.chat.McpServerDefinition
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class ToolManager(
    private val builtinToolRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    fun resolve(
        customTools: List<CustomTool>,
        mcpServers: List<McpServer>,
        builtinSettings: List<BuiltinToolSetting>,
        mcpCachedTools: Map<String, List<McpTool>> = emptyMap(),
    ): ResolvedTools {
        val builtinTools = buildBuiltinTools(builtinSettings)
        val customRuntimeTools = buildCustomTools(customTools)
        val mcpRuntimeServers = buildMcpServers(
            servers = mcpServers,
            cachedTools = mcpCachedTools,
        )

        return ResolvedTools(
            builtinTools = builtinTools,
            customTools = customRuntimeTools,
            mcpServers = mcpRuntimeServers,
        )
    }

    private fun buildBuiltinTools(settings: List<BuiltinToolSetting>): List<LocalTool.Builtin> {
        return settings
            .filter { it.enabled }
            .sortedBy { it.name }
            .mapNotNull { setting ->
                val tool = findBuiltinTool(setting.name) ?: return@mapNotNull null
                LocalTool.Builtin(
                    name = setting.name,
                    description = setting.description,
                    tool = tool,
                )
            }
    }

    private fun findBuiltinTool(name: String): BuiltinTool? {
        return builtinToolRegistry.find(name)
            ?: builtinToolRegistry.all().firstOrNull { it::class.simpleName == name }
    }

    private fun buildCustomTools(tools: List<CustomTool>): List<LocalTool.Custom> {
        return tools
            .filter { it.enabled }
            .map { tool ->
                LocalTool.Custom(
                    name = tool.name,
                    description = tool.description.withCustomShellGuidance(),
                    enabled = tool.enabled,
                    command = tool.command,
                )
            }
            .associateBy(LocalTool.Custom::name)
            .values
            .toList()
    }

    private fun buildMcpServers(
        servers: List<McpServer>,
        cachedTools: Map<String, List<McpTool>>,
    ): List<McpServerDefinition> {
        return servers.map { server ->
            McpServerDefinition.Http(
                name = server.name,
                url = server.url,
                enabled = server.enabled,
                headers = server.headers,
                cachedTools = cachedTools[server.name].orEmpty().map(::toCachedTool),
            )
        }
    }

    private fun toCachedTool(tool: McpTool): McpCachedTool {
        return McpCachedTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchemaJson.asJsonObjectOrEmpty(),
        )
    }

    private fun String.asJsonObjectOrEmpty(): JsonObject {
        if (isBlank()) {
            return JsonObject(emptyMap())
        }
        return try {
            Json.parseToJsonElement(this).jsonObject
        } catch (_: SerializationException) {
            JsonObject(emptyMap())
        } catch (_: IllegalArgumentException) {
            JsonObject(emptyMap())
        }
    }

    private fun String.withCustomShellGuidance(): String {
        return "$this\nRuns in an Android shell via the terminal tool. Use identity=root for root shell, " +
                "identity=shizuku for Shizuku, or identity=user for unprivileged shell. " +
                "If the command depends on a working directory, create it as `cd /path && cmd`."
    }

}
