package com.niki914.okai

import com.niki914.okai.event.TurnEvent
import com.niki914.okai.mcp.McpDiscoverySnapshot
import com.niki914.okai.mcp.McpRefreshResult
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.ChatProtocol
import com.niki914.okai.runtime.OkaiDependencies
import kotlin.reflect.KClass

/**
 * Facade over the whole library: one instance hosts one conversation and at
 * most one active turn. send runs the turn in a child job and commits its
 * delta to the session, so the turn result is committed on every path:
 * stop cancels the child job only and suspends until cleanup finishes, an
 * external cancellation of the caller's coroutine is rethrown after the
 * commit. fork returns a new independent instance for a new conversation;
 * rewind moves this conversation back to a past entry. Hosts switch
 * conversations by building instances with open() and a session loaded
 * through dependencies, not by reusing one instance for multiple sessions.
 *
 * Design source: independent facade design; single-conversation hosting and
 * fork semantics from pi (earendil-works/pi session-manager) and codex
 * thread-store, surface validated in the Nexus usage of kai, per kai PRD.
 */
interface Okai {

    suspend fun send(
        text: String,
        options: TurnOptions? = null,
        onEvent: suspend (TurnEvent) -> Unit
    )

    suspend fun stop()

    /** New instance hosting a new conversation forked from this one's current path. */
    suspend fun fork(): Okai

    /** Moves this conversation's current position back to a past entry. */
    suspend fun rewind(entryId: String)

    suspend fun update(block: OkaiConfig.Builder.() -> Unit)

    suspend fun config(): OkaiConfig

    suspend fun getHistory(): List<Message>

    suspend fun replaceHistory(history: List<Message>)

    suspend fun resetConversation()

    suspend fun refreshMcpTools(): McpRefreshResult

    suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot

    suspend fun close()

    companion object {

        suspend fun open(
            protocolClass: KClass<out ChatProtocol>,
            builder: OkaiConfig.Builder.() -> Unit
        ): Okai = TODO()

        suspend fun open(
            builder: OkaiConfig.Builder.() -> Unit
        ): Okai = TODO()

        suspend fun open(
            dependencies: OkaiDependencies,
            builder: OkaiConfig.Builder.() -> Unit
        ): Okai = TODO()
    }
}
