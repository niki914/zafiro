package com.niki914.okai.tool

/**
 * Executes one tool call. The chain terminates here. Hosts implement local
 * executors; an MCP executor with an HTTP-only client arrives in M1.
 * Interceptors must not depend on which executor type runs.
 *
 * Design source: kai PRD sections 2 and 4.5 ToolExecutor abstraction.
 */
interface ToolExecutor {

    suspend fun execute(call: ToolCallContext): ToolCallOutcome
}
