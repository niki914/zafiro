package com.niki914.okai.tool

import com.niki914.okai.message.ToolCallOutcome
import com.niki914.okai.session.Session

/**
 * Immutable view of one tool call flowing through the interceptor chain.
 * The descriptor carries the registered kind, so executors route to local
 * or MCP without re-resolving the registry. attempt starts at 1 and
 * increments on retry, letting interceptors see how many times this call
 * has already run.
 *
 * Design source: kai PRD section 4.5 ToolCallContext; descriptor routing
 * required by the McpExecutor (M1).
 */
data class ToolCallContext(
    val id: String,
    val name: String,
    val descriptor: ToolDescriptor,
    val argumentsJson: String,
    val attempt: Int,
    val session: Session?
)
