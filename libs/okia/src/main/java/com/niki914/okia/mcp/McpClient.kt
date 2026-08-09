package com.niki914.okia.mcp

/**
 * MCP 线缆客户端：在服务器上发现工具并执行调用。具体 HTTP 客户端在 M1，
 * 此接口让 loop 不接触任何 MCP 传输知识。仅 HTTP 传输。
 * Design source: codex（protocol/src/mcp.rs），kai PRD §2 / §5。
 */
interface McpClient {

    // 发现服务器上的工具
    suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> = TODO()

    // 调用服务器上的工具，返回结果文本
    suspend fun callTool(server: McpServer, toolName: String, argumentsJson: String): String = TODO()
}

/** 在 MCP 服务器上发现的工具，喂入工具注册表。 */
data class McpDiscoveredTool(
    val name: String,
    val description: String?,
    val inputSchemaJson: String?
)
