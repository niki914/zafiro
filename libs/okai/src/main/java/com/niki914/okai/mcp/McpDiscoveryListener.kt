package com.niki914.okai.mcp

/**
 * Callback fired after each server discovery. Hosts use it to persist
 * discovered tools, while refresh decisions stay inside the library.
 *
 * Design source: independent design; callback role validated in the Nexus
 * usage of kai, per kai PRD section 5.
 */
interface McpDiscoveryListener {

    suspend fun onToolsDiscovered(serverName: String, tools: List<McpDiscoveredTool>)
}
