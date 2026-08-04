package com.niki914.okai.loop

import com.niki914.okai.event.FinishReason
import com.niki914.okai.event.TurnEvent
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.RequestSnapshot

/**
 * The turn engine: model call, tool execution loop and segment handling.
 * Injectable so tests drive the loop with a fake protocol and fake transport.
 *
 * Design source: pi (earendil-works/pi) agentLoop and codex run_turn,
 * per kai PRD section 4.4.
 */
interface AgentLoop {

    suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): FinishReason
}

/** Immutable inputs for one turn execution. */
data class LoopRequest(
    val snapshot: RequestSnapshot,
    val history: List<Message>,
    val input: String,
    val options: LoopOptions
)
