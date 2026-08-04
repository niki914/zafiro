package com.niki914.okai

import com.niki914.okai.event.TurnEvent
import com.niki914.okai.loop.StopMode
import com.niki914.okai.mcp.McpDiscoverySnapshot
import com.niki914.okai.mcp.McpRefreshResult
import com.niki914.okai.message.Message
import com.niki914.okai.protocol.ChatProtocol
import kotlin.reflect.KClass

/**
 * Facade over the whole library. The only entry point hosts need; everything
 * else is reached through config, interceptors and events.
 *
 * Design source: existing kai (s3ss10n) Kai interface, restructured per kai PRD.
 */
interface Okai {

    suspend fun send(
        text: String,
        options: TurnOptions? = null,
        onEvent: suspend (TurnEvent) -> Unit
    )

    suspend fun stop(mode: StopMode = StopMode.Force, keepCurrentTurn: Boolean = false)

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
    }
}
