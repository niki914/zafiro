package com.niki914.okai.mcp

/**
 * MCP server config. Transport is sealed so future SSE/WebSocket variants
 * extend without touching the loop. M0 ships HTTP only, matching the
 * current kai MCP client.
 *
 * Design source: existing kai (s3ss10n) McpServerDefinition, per kai PRD section 2.
 */
data class McpServer(
    val name: String,
    val transport: McpTransport,
    val headers: Map<String, String>,
    val enabled: Boolean
)

/** How a client reaches one MCP server. */
sealed interface McpTransport {

    /** HTTP (streamable or SSE) transport. */
    data class Http(val url: String) : McpTransport
}
