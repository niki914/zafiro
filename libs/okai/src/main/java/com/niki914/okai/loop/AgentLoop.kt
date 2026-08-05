package com.niki914.okai.loop

import com.niki914.okai.event.FinishReason
import com.niki914.okai.event.TurnEvent
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.RequestSnapshot

/**
 * The turn engine: model call, tool execution loop and segment handling.
 * Stateless: history comes in with the request, the complete turn delta
 * comes back as TurnResult, which the caller commits to the session.
 * Stop is force-only: cancelling the coroutine that runs this turn
 * propagates to every suspension point (stream collection, tool
 * execution, retry delay). On cancellation the loop finishes in a
 * NonCancellable context: it commits produced content, calls the force
 * stop hook once, and produces a terminal outcome for every pending tool
 * call in the partial assistant message (calls never dispatched into the
 * chain are marked Interrupted by the loop itself, dispatched ones go
 * through ToolExecutor.interruptedOutcome), so the returned TurnResult
 * leaves the history well-formed for the next request.
 * run never throws CancellationException: the loop cannot tell an
 * internal stop from an external cancellation, both look like a cancelled
 * suspension point, so cancellation surfaces as FinishReason.Aborted in
 * the returned TurnResult after cleanup, and the Okai facade, the only
 * party that knows the cancel source, decides whether to rethrow. Idle
 * timeout returns FinishReason.IdleTimeout. Registry and chain references
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
    ): TurnResult
}

/**
 * The whole turn's message delta for the caller to commit, in emission
 * order. A turn interleaves assistant messages and tool results: the
 * model may emit several rounds with tool calls between them, and one
 * assistant message mixes text, thinking and tool call blocks. On
 * cancellation every pending tool call has a terminal result, so the
 * committed history is complete and the model sees the interruption.
 */
data class TurnResult(
    val messages: List<Message>,
    val reason: FinishReason
)

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
