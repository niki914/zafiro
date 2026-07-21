package com.niki914.nexus.agentic.chat.agentic.mcp

import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.McpDiscoveredTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class McpDiscoveryCacheStore {
    suspend fun onToolsDiscovered(
        serverName: String,
        tools: List<McpDiscoveredTool>,
    ) {
        try {
            persistDiscoveredTools(serverName = serverName, tools = tools)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
        }
    }

    private suspend fun persistDiscoveredTools(
        serverName: String,
        tools: List<McpDiscoveredTool>,
    ) {
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val server = gateway.listMcpServers().firstOrNull { it.name == serverName } ?: return
        gateway.saveDiscoveredTools(
            url = server.url,
            headers = server.headers,
            tools = tools.map { it.toRuntimeTool() },
        )
    }

    private fun McpDiscoveredTool.toRuntimeTool(): McpTool =
        McpTool(
            name = name,
            description = description,
            inputSchemaJson = inputSchema.toJsonElement().toString(),
        )

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is JsonElement -> this
            is String -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Map<*, *> -> JsonObject(
                entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value.toJsonElement() }
                }.toMap()
            )

            is Iterable<*> -> JsonArray(map { it.toJsonElement() })
            is Array<*> -> JsonArray(map { it.toJsonElement() })
            else -> JsonPrimitive(toString())
        }

    companion object {
        const val MCP_DISCOVERED_TOOLS_CACHE_KEY: String = "mcp_discovered_tools_cache"
    }
}
