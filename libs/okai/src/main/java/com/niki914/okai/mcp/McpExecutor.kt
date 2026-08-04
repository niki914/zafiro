package com.niki914.okai.mcp

import com.niki914.okai.tool.ToolCallContext
import com.niki914.okai.tool.ToolCallOutcome
import com.niki914.okai.tool.ToolExecutor

/**
 * Tool executor for MCP tools, terminating the interceptor chain for Mcp kind.
 * Concrete implementation arrives in M1 reusing the existing kai MCP client;
 * the slot is declared now so the chain design stays explicit.
 *
 * Design source: kai PRD sections 4.5 (executor after the chain) and 2 (MCP reuse).
 */
class McpExecutor(
    private val client: McpClient
) : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()
}
