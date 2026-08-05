package com.niki914.okai.tool

import com.niki914.okai.message.ToolCallOutcome

/**
 * Executes one tool call. The chain terminates here. Hosts implement local
 * executors; an MCP executor with an HTTP-only client arrives in M1.
 * Interceptors must not depend on which executor type runs.
 *
 * Cancellation contract: when the running turn is cancelled, the suspend
 * point inside execute throws CancellationException and the call bubbles
 * up. Outcome responsibility splits by who owns the facts: calls never
 * dispatched into the chain are marked Interrupted by the loop itself
 * (never executed is the loop's own fact); dispatched calls go through
 * interruptedOutcome here, where the implementation judges from its own
 * internal state whether the call never ran (Interrupted) or may have
 * executed remotely (Unknown, never retried, e.g. a remote call cancelled
 * while awaiting its response). The loop feeds the outcomes back to the
 * history as ToolResult messages, so the next request stays well-formed
 * and the model sees the interruption. The library never fabricates an
 * outcome for a dispatched call.
 *
 * Design source: kai PRD sections 2 and 4.5 ToolExecutor abstraction;
 * interruption outcome requirement from the Nexus stop handling.
 */
interface ToolExecutor {

    suspend fun execute(call: ToolCallContext): ToolCallOutcome

    fun interruptedOutcome(call: ToolCallContext): ToolCallOutcome
}
