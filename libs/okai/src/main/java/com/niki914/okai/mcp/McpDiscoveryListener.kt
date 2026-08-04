package com.niki914.okai.mcp

/**
 * Callback fired after each server discovery. Hosts use it to persist
 * discovered tools (the existing kai McpHooks.onToolsDiscovered role),
 * while refresh decisions stay inside the library.
 *
 * Design source: existing kai (s3ss10n) McpHooks, per kai PRD section 5.
 */
interface McpDiscoveryListener {

    suspend fun onToolsDiscovered(serverName: String, tools: List<McpDiscoveredTool>)
}
