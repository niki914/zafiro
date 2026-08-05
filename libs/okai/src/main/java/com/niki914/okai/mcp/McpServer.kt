package com.niki914.okai.mcp

/**
 * MCP server config. Transport is sealed so future transports extend
 * without touching the loop. HTTP only; local-process and Node-based
 * transports are out of scope for Android/JVM.
 *
 * Design source: independent design; config shape validated in the Nexus
 * usage of kai, per kai PRD section 2.
 */
data class McpServer(
    val name: String,
    val transport: McpTransport,
    val headers: Map<String, String>,
    val enabled: Boolean
)

/** How a client reaches one MCP server. */
sealed interface McpTransport {

    /** HTTP transport; concrete framing (streamable or SSE) is an M1 client detail. */
    data class Http(val url: String) : McpTransport
}
