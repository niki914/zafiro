package com.niki914.kai

import com.niki914.kai.ext.protocol.ChatProtocol
import com.niki914.kai.ext.protocol.ProtocolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.reflect.KClass

interface Kai {
    suspend fun send(
        text: String,
        onEvent: suspend (KaiEvent) -> Unit
    )

    fun send(text: String): Flow<KaiEvent> = channelFlow {
        this@Kai.send(text) { send(it) }
    }

    suspend fun stop(keepCurrentTurn: Boolean = false)

    suspend fun getHistory(): List<ChatTurn>

    suspend fun replaceHistory(history: List<ChatTurn>): Unit

    suspend fun resetConversation()

    suspend fun close()

    suspend fun update(block: KaiConfig.Builder.() -> Unit)

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
            builder: KaiConfig.Builder.() -> Unit
        ): Kai {
            KaiProviderProtocols.ensureInitialized()
            val config = KaiConfig.Builder().apply(builder).build()

            var protocol = ProtocolRegistry.resolve(protocolClass)
            if (config.jsonCodec != null) {
                protocol = protocol.withCodec(config.jsonCodec!!)
            }

            return OKai(initialConfig = config, protocol = protocol)
        }

        suspend inline fun <reified P : ChatProtocol> open(
            noinline builder: KaiConfig.Builder.() -> Unit
        ): Kai = open(P::class, builder)

        @JvmName("openDefault")
        suspend fun open(
            builder: KaiConfig.Builder.() -> Unit
        ): Kai = open(KaiProviderProtocols.OpenAI::class, builder)
    }
}
