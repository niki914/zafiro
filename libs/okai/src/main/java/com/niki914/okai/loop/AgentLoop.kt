package com.niki914.okai.loop

import com.niki914.okai.event.FinishReason
import com.niki914.okai.event.StopCause
import com.niki914.okai.event.TurnEvent
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.RequestSnapshot
import com.niki914.okai.runtime.Clock
import com.niki914.okai.session.Session
import com.niki914.okai.tool.ToolCallInterceptor
import com.niki914.okai.tool.ToolRegistry

/**
 * The turn engine: model call, tool execution loop and segment handling.
 * Stateless: history comes in with the request, the complete turn delta
 * comes back as TurnResult, which the caller commits to the session.
 * Stop is force-only: cancelling the coroutine that runs this turn
 * propagates to every suspension point (stream collection, tool
 * execution, retry delay). On cancellation the loop finishes in a
 * NonCancellable context: it produces a terminal outcome for every
 * pending tool call in the partial assistant message (calls never
 * dispatched into the chain are marked Interrupted by the loop itself,
 * dispatched ones go through ToolExecutor.interruptedOutcome; tool calls
 * that never reached ToolCallReady are dropped, not persisted), so the
 * returned TurnResult leaves the history well-formed for the next
 * request.
 * run never throws CancellationException: the loop cannot tell an
 * internal stop from an external cancellation, both look like a cancelled
 * suspension point, so cancellation surfaces as FinishReason.Aborted
 * with the cause the coordinator recorded when it cancelled the job
 * (StopCause.UserStop, Replace or External; idle timeout returns
 * FinishReason.IdleTimeout with cause null). The Okai facade, the only
 * party that knows the cancel source, decides whether to rethrow. The
 * force stop hook is not called here: the coordinator runs it before
 * cancelling the job (kill-then-stop). Session, registry and chain
 * references come in with the request, so tests drive the loop with
 * fakes.
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
 * cause is set when the turn was cancelled; a normal end carries null.
 */
data class TurnResult(
    val messages: List<Message>,
    val reason: FinishReason,
    val cause: StopCause? = null
)

/**
 * Immutable inputs for one turn execution. idleTimeoutSeconds bounds the
 * model stream only: any arriving frame (text and thinking deltas, tool
 * call chunks, keep-alive comments) resets the timer, and tool execution
 * time does not count against it. Session, registry, chain and clock
 * come from OkaiDependencies, assembled by the Okai coordinator, so the
 * loop reads them without owning them.
 */
data class LoopRequest(
    val snapshot: RequestSnapshot,
    val history: List<Message>,
    val input: String,
    val options: LoopOptions,
    val idleTimeoutSeconds: Long?,
    val session: Session,
    val toolRegistry: ToolRegistry,
    val interceptors: List<ToolCallInterceptor>,
    val clock: Clock
)
