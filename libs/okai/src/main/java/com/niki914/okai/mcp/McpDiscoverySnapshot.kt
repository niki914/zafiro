package com.niki914.okai.mcp

/**
 * Current discovery state per server. Hosts read this to compose prompts
 * or persist discovered tools; the library refreshes it whenever config changes.
 *
 * Design source: existing kai (s3ss10n) McpDiscoverySnapshot, per kai PRD section 5
 * (MCP lifecycle sinks into the library, host only reads).
 */
data class McpDiscoverySnapshot(
    val servers: Map<String, List<McpDiscoveredTool>>,
    val failedServers: List<String>
)

/**
 * Outcome of one explicit refresh. Hosts use failedServers to surface
 * connectivity problems without parsing exceptions.
 *
 * Design source: existing kai (s3ss10n) McpRefreshResult.
 */
data class McpRefreshResult(
    val refreshedServers: List<String>,
    val failedServers: List<String>
)
