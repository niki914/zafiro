package com.niki914.nexus.agentic.chat.agentic

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.McpServerDefinition
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import kotlinx.serialization.json.JsonObject
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer

class ToolManager(
    private val builtinToolRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    private companion object {
        const val LOG_TAG = "niki914_nexus_ToolManager"
    }

    fun resolve(
        customTools: List<CustomTool>,
        mcpServers: List<McpServer>,
        builtinSettings: List<BuiltinToolSetting>,
    ): ResolvedTools {
        val builtinTools = buildBuiltinTools(builtinSettings)
        val customRuntimeTools = buildCustomTools(customTools)
        val mcpRuntimeServers = buildMcpServers(servers = mcpServers)

        Logger.d(
            LOG_TAG,
            "tools resolve builtin=${builtinTools.size} custom=${customRuntimeTools.size} " +
                "mcp=${mcpRuntimeServers.size} " +
                "input builtinSettings=${builtinSettings.size} customTools=${customTools.size} " +
                "mcpServers=${mcpServers.size}"
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

    private fun String.withCustomShellGuidance(): String {
        return "$this\nRuns in an unprivileged Android shell (fixed user identity). " +
                "For commands that need root or Shizuku privileges, use the terminal builtin tool " +
                "with identity=root or identity=shizuku instead. " +
                "If the command depends on a working directory, create it as `cd /path && cmd`."
    }

}
