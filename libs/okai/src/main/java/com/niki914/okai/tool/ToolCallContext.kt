package com.niki914.okai.tool

import com.niki914.okai.session.Session

/**
 * Immutable view of one tool call flowing through the interceptor chain.
 * attempt starts at 1 and increments on retry, letting interceptors see
 * how many times this call has already run.
 *
 * Design source: kai PRD section 4.5 ToolCallContext.
 */
data class ToolCallContext(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val attempt: Int,
    val session: Session?
)

/**
 * Terminal result of a tool call, either from an interceptor short-circuit
 * or from the executor.
 *
 * Design source: kai PRD section 4.5 ToolCallOutcome; Blocked aligns with
 * codex ReviewDecision::Denied / hook PermissionRequestDecision::Deny.
 */
sealed interface ToolCallOutcome {

    /** Tool succeeded with a result payload. */
    data class Success(val contentJson: String) : ToolCallOutcome

    /** Tool ran but failed. */
    data class Failure(val message: String, val contentJson: String? = null) : ToolCallOutcome

    /** Tool was refused before execution. The loop feeds this back to the model. */
    data class Blocked(val reason: String) : ToolCallOutcome
}
