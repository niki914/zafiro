package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.McpServerDefinition
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.allLocalToolNames
import com.niki914.kai.SessionConfig

object SessionToolBinder {
    fun SessionConfig.Builder.bindTools(
        tools: ResolvedTools,
        previousTools: ResolvedTools?,
    ) {
        localTools {
            previousTools?.allLocalToolNames().orEmpty().forEach(::remove)
            tools.builtinTools
                .filterIsInstance<LocalTool.Builtin>()
                .forEach { tool ->
                    add(tool.name) {
                        tool.tool.configure(this)
                    }
                }
            tools.customTools
                .filterIsInstance<LocalTool.Custom>()
                .forEach { tool ->
                    add(tool.name) {
                        description = tool.description
                    }
                }
        }

        mcp {
            previousTools?.mcpServers.orEmpty().forEach { remove(it.name) }
            tools.mcpServers.forEach { server ->
                when (server) {
                    is McpServerDefinition.Http -> add(server.name) {
                        enabled = server.enabled
                        headers = server.headers
                        http { url = server.url }
                        server.cachedTools.forEach { cachedTool ->
                            tool(cachedTool.name) {
                                description = cachedTool.description
                                rawJsonSchema(cachedTool.inputSchema.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}
