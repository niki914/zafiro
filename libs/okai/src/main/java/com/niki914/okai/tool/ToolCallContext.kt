package com.niki914.okai.tool

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

/**
 * Terminal result of a tool call, either from an interceptor short-circuit
 * or from the executor. Interrupted and Unknown are the outcomes of a
 * cancelled turn: the loop feeds them back as error tool results so the
 * model sees the interruption, and they are never retried.
 *
 * Design source: kai PRD section 4.5 ToolCallOutcome; Blocked aligns with
 * codex ReviewDecision::Denied / hook PermissionRequestDecision::Deny;
 * Interrupted and Unknown cover the cancellation cases from the force-only
 * stop design (kai PRD section 4.4).
 */
sealed interface ToolCallOutcome {

    /** Tool succeeded with a result payload. Content is arbitrary text, not necessarily JSON. */
    data class Success(val content: String) : ToolCallOutcome

    /** Tool ran but failed. */
    data class Failure(val message: String, val content: String? = null) : ToolCallOutcome

    /** Tool was refused before execution. The loop feeds this back to the model. */
    data class Blocked(val reason: String) : ToolCallOutcome

    /** Tool was interrupted before or during execution; nothing ran to completion. */
    data class Interrupted(val content: String) : ToolCallOutcome

    /** Execution state unknown, e.g. a remote call may have run before cancellation. Never retried. */
    data class Unknown(val message: String, val content: String? = null) : ToolCallOutcome
}
