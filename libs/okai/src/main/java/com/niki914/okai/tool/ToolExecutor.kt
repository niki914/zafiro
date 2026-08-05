package com.niki914.okai.tool

/**
 * Executes one tool call. The chain terminates here. Hosts implement local
 * executors; an MCP executor with an HTTP-only client arrives in M1.
 * Interceptors must not depend on which executor type runs.
 *
 * Cancellation contract: when the running turn is cancelled, the suspend
 * point inside execute throws CancellationException and the call bubbles
 * up. The loop then calls interruptedOutcome for every pending tool call
 * in the partial assistant message and commits the outcomes to the
 * history, so the next request stays well-formed and the model sees the
 * interruption. Implementations return Interrupted or Unknown here to
 * mark the call as never retried; the library never fabricates the
 * outcome for them. Interrupted covers calls that did not run, Unknown
 * covers calls whose side effects may have executed (e.g. a remote call
 * cancelled while awaiting its response).
 *
 * Design source: kai PRD sections 2 and 4.5 ToolExecutor abstraction;
 * interruption outcome requirement from the Nexus stop handling.
 */
interface ToolExecutor {

    suspend fun execute(call: ToolCallContext): ToolCallOutcome

    fun interruptedOutcome(call: ToolCallContext): ToolCallOutcome
}
