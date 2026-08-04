package com.niki914.okai.mcp

/**
 * MCP wire client: discovers tools and executes calls on a server.
 * The concrete HTTP client lands in M1; this interface keeps the loop free
 * of any MCP transport knowledge. HTTP transport only.
 *
 * Design source: codex (openai/codex protocol/src/mcp.rs), per kai PRD
 * sections 2 and 5.
 */
interface McpClient {

    suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool>

    suspend fun callTool(server: McpServer, toolName: String, argumentsJson: String): String
}

/** A tool discovered on an MCP server, fed into the tool registry. */
data class McpDiscoveredTool(
    val name: String,
    val description: String?,
    val inputSchemaJson: String?
)
