package com.niki914.zafiro.chat.agentic

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.McpServerDefinition
import com.niki914.zafiro.chat.ResolvedTools
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import kotlinx.serialization.json.JsonObject
import com.niki914.zafiro.settings.model.RuntimePyTool
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer

class ToolManager(
    private val builtinToolRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    private companion object {
        const val LOG_TAG = "niki914_nexus_ToolManager"
    }

    fun resolve(
        pyTools: List<RuntimePyTool>,
        mcpServers: List<McpServer>,
        builtinSettings: List<BuiltinToolSetting>,
    ): ResolvedTools {
        val builtinTools = buildBuiltinTools(builtinSettings)
        val pyRuntimeTools = buildPyTools(pyTools)
        val mcpRuntimeServers = buildMcpServers(servers = mcpServers)

        Logger.d(
            LOG_TAG,
            "tools resolve builtin=${builtinTools.size} py=${pyRuntimeTools.size} " +
                "mcp=${mcpRuntimeServers.size} " +
                "input builtinSettings=${builtinSettings.size} pyTools=${pyTools.size} " +
                "mcpServers=${mcpServers.size}"
        )

        return ResolvedTools(
            builtinTools = builtinTools,
            pyTools = pyRuntimeTools,
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

    private fun buildPyTools(tools: List<RuntimePyTool>): List<LocalTool.Py> {
        return tools
            .filter { it.enabled }
            .map { tool ->
                LocalTool.Py(
                    name = tool.name,
                    description = tool.description.ifBlank { "Python tool ${tool.name}." },
                    code = tool.code,
                    inputSchemaJson = tool.schemaJson.ifBlank { null },
                    timeoutMs = tool.timeoutMs,
                )
            }
    }

    private fun buildMcpServers(
        servers: List<McpServer>,
    ): List<McpServerDefinition> {
        return servers.map { server ->
            McpServerDefinition.Http(
                name = server.name,
                url = server.url,
                enabled = server.enabled,
                headers = server.headers,
            )
        }
    }

}
