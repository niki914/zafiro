package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.reflect.KClass

interface Session {
    suspend fun send(
        text: String,
        onEvent: suspend (SessionEvent) -> Unit
    )

    fun send(text: String): Flow<SessionEvent> = channelFlow {
        this@Session.send(text) { send(it) }
    }

    suspend fun stop(keepCurrentTurn: Boolean = false)

    suspend fun getHistory(): List<ChatTurn>

    suspend fun replaceHistory(history: List<ChatTurn>): Unit

    suspend fun resetConversation()

    suspend fun close()

    suspend fun update(block: SessionConfig.Builder.() -> Unit)

    suspend fun refreshMcpTools(): McpRefreshResult

    suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot {
        return McpDiscoverySnapshot(
            servers = emptyMap(),
            finalToolRegistry = ToolRegistrySnapshot.Empty
        )
    }

    companion object {
        suspend fun <P : ChatProtocol> open(
            protocolClass: KClass<P>,
            builder: SessionConfig.Builder.() -> Unit
        ): Session {
            SessionProtocols.ensureInitialized()
            val config = SessionConfig.Builder().apply(builder).build()

            var protocol = ProtocolRegistry.resolve(protocolClass)
            if (config.jsonCodec != null) {
                protocol = protocol.withCodec(config.jsonCodec!!)
            }

            return ChatSession(initialConfig = config, protocol = protocol)
        }

        suspend inline fun <reified P : ChatProtocol> open(
            noinline builder: SessionConfig.Builder.() -> Unit
        ): Session = open(P::class, builder)

        @JvmName("openDefault")
        suspend fun open(
            builder: SessionConfig.Builder.() -> Unit
        ): Session = open(SessionProtocols.OpenAI::class, builder)
    }
}
