package com.niki914.okai.loop

import com.niki914.okai.event.FinishReason
import com.niki914.okai.event.TurnEvent
import com.niki914.okai.message.AssistantMessage
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
 * call in the partial assistant message (including calls never started),
 * so the returned TurnResult leaves the history well-formed for the next
 * request. An internal stop or idle timeout returns normally with
 * FinishReason.Aborted or IdleTimeout; external cancellation rethrows the
 * CancellationException after cleanup. Registry and chain references
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
 * The whole turn's delta for the caller to commit: the final assistant
 * message and one terminal result per tool call issued in this turn,
 * including interrupted ones. On cancellation every pending call has a
 * result, so the committed history is complete and the model sees the
 * interruption in the next request.
 */
data class TurnResult(
    val message: AssistantMessage,
    val toolResults: List<Message.ToolResult>,
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
