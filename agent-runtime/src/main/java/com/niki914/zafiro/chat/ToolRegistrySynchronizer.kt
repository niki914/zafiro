package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.chat.agentic.LocalToolExecutor

/**
 * OKIA 工具注册表同步与管理。
 *
 * 维护本地工具（builtin + py）在 OKIA ToolRegistry 中的注册与生命周期，
 * 支持回合内动态注册自定义 py 工具，以及 MCP 服务器数据结构的转换与签名计算。
 */
internal class ToolRegistrySynchronizer(
    private val currentTools: () -> ResolvedTools?,
) {
    private companion object {
        const val LOG_TAG = "niki914_zafiro_ToolRegistrySynchronizer"
    }

    val toolRegistry: ToolRegistry = DefaultToolRegistry()

    val inlineCustomPyTools = mutableMapOf<String, LocalTool.Py>()

    private val localToolExecutor = LocalToolExecutor(
        currentTools = currentTools,
        inlineCustomPyTools = inlineCustomPyTools,
        onCustomPyToolWritten = { tool -> registerCustomPyToolNow(tool) },
    )

    fun reset() {
        toolRegistry.snapshot().forEach { toolRegistry.remove(it.descriptor.wireName) }
        inlineCustomPyTools.clear()
    }

    /**
     * 全量重建本地工具注册：registry 中所有 Local 工具先移除（含 inline 的），
     * 再注册当前 resolved 的 enabled 工具。
     */
    fun syncLocalTools(tools: ResolvedTools) {
        toolRegistry.snapshot()
            .map { it.descriptor }
            .filter { it.kind is ToolKind.Local }
            .forEach { toolRegistry.remove(it.wireName) }
        (tools.builtinTools + tools.customPyTools).forEach { tool ->
            val inputSchemaJson = when (tool) {
                is LocalTool.Builtin -> tool.tool.inputSchemaJson
                is LocalTool.Py -> tool.inputSchemaJson
            }
            toolRegistry.register(
                ToolDescriptor(
                    name = tool.name,
                    description = tool.description,
                    inputSchemaJson = inputSchemaJson,
                    kind = ToolKind.Local,
                ),
                localToolExecutor,
            )
        }
        inlineCustomPyTools.clear()
    }

    /**
     * py_meta_tools write 成功且 enabled 的回合内注册（D20）：立即注册进 registry。
     */
    fun registerCustomPyToolNow(tool: LocalTool.Py) {
        toolRegistry.register(
            ToolDescriptor(
                name = tool.name,
                description = tool.description,
                inputSchemaJson = tool.inputSchemaJson,
                kind = ToolKind.Local,
            ),
            localToolExecutor,
        )
        Logger.i(
            LOG_TAG,
            "custom py tool registered in-turn name=${tool.name}"
        )
    }

    /** McpServerDefinition.Http → OKIA McpServer（字段一一对应，T2b）。 */
    fun toOkiaMcpServers(servers: List<McpServerDefinition>): List<McpServer> {
        return servers.mapNotNull { server ->
            when (server) {
                is McpServerDefinition.Http ->
                    McpServer(
                        name = server.name,
                        transport = McpTransport.Http(server.url),
                        headers = server.headers,
                        enabled = server.enabled,
                    )
            }
        }
    }

    /** 服务器配置签名：对 McpServerDefinition.Http（name/url/headers/enabled）确定性序列化。 */
    fun mcpServersSignature(servers: List<McpServerDefinition>): String {
        return servers
            .sortedBy { it.name }
            .joinToString(separator = "\n") { server ->
                when (server) {
                    is McpServerDefinition.Http -> {
                        val headers = server.headers
                            .mapKeys { (key, _) -> key.lowercase() }
                            .toSortedMap()
                            .entries
                            .joinToString(separator = "&") { (key, value) -> "$key=$value" }
                        "${server.name}|${server.url}|${server.enabled}|$headers"
                    }
                }
            }
    }
}
