package com.niki914.okai.mcp

import com.niki914.okai.message.ToolCallOutcome
import com.niki914.okai.tool.ToolCallContext
import com.niki914.okai.tool.ToolExecutor

/**
 * Tool executor for MCP tools, terminating the interceptor chain for Mcp kind.
 * Routes via the descriptor's server name; the resolver returns the current
 * server config so config updates stay visible. Concrete implementation
 * arrives in M1 with an HTTP-only client; the slot is declared now so the
 * chain design stays explicit.
 *
 * Design source: codex (openai/codex) tool execution, per kai PRD sections
 * 4.5 (executor after the chain) and 2 (HTTP-only MCP).
 */
class McpExecutor(
    private val client: McpClient,
    private val servers: (serverName: String) -> McpServer?
) : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    override fun interruptedOutcome(call: ToolCallContext): ToolCallOutcome = TODO()
}
