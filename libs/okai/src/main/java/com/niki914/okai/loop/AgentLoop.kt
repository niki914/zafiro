package com.niki914.okai.loop

import com.niki914.okai.event.FinishReason
import com.niki914.okai.event.TurnEvent
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.RequestSnapshot

/**
 * The turn engine: model call, tool execution loop and segment handling.
 * Stop is force-only: cancelling the coroutine that runs this turn
 * propagates to every suspension point (stream collection, tool
 * execution, retry delay). On cancellation the loop still commits
 * produced content and each interrupted tool's outcome, so the history
 * stays well-formed for the next request. Registry and chain references
 * come from OkaiDependencies, so tests drive the loop with fakes.
 *
 * Design source: pi (earendil-works/pi) agentLoop and codex run_turn,
 * cancellation modelled on Kotlin coroutine semantics per kai PRD
 * section 4.4.
 */
interface AgentLoop {

    suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): FinishReason
}

/**
 * Immutable inputs for one turn execution. idleTimeoutSeconds bounds the
 * model stream only: any arriving frame (text and thinking deltas, tool
 * call chunks, keep-alive comments) resets the timer, and tool execution
 * time does not count against it.
 */
data class LoopRequest(
    val snapshot: RequestSnapshot,
    val history: List<Message>,
    val input: String,
    val options: LoopOptions,
    val idleTimeoutSeconds: Long?
)
