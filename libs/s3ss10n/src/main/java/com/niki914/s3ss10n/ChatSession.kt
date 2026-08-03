package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodecFactory
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.ext.net.OkHttpEngine
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.util.HistoryKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ConfigInvalidException :
    IllegalAccessException("Config is invalid. Set endpoint and model first!")

class ChatSession internal constructor(
    initialConfig: SessionConfig,
    private val protocol: ChatProtocol
) : Session {

    private val state: SessionState = SessionState(initialConfig)
    private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()
    private val jsonCodec: JsonCodec = initialConfig.jsonCodec ?: JsonCodecFactory.create()

    private suspend fun thisConfig(): SessionConfig = state.currentConfig()
    private val mcpClient: McpClient = HttpMcpClient(engine = engine, codec = jsonCodec)
    private val toolCallCoordinator = ToolCallCoordinator(mcpClient = mcpClient, codec = jsonCodec)
    private val scope = CoroutineScope(SupervisorJob())
    private val mcpDiscoveryCoordinator = McpDiscoveryCoordinator(
        mcpClient = mcpClient,
        scope = scope,
        codec = jsonCodec,
        latestConfig = ::thisConfig
    )
    private val historyKeeper = HistoryKeeper()
    private val roundRunner = RoundRunner(
        protocol = protocol,
        engine = engine,
        historyKeeper = historyKeeper,
        toolCallCoordinator = toolCallCoordinator,
        scope = scope
    )

    init {
        initialConfig.localToolRegistry.codec = jsonCodec
        mcpDiscoveryCoordinator.scheduleDiscovery(initialConfig)
    }

    override suspend fun send(text: String, onEvent: suspend (SessionEvent) -> Unit) {
        val config = state.currentConfig()
        mcpDiscoveryCoordinator.scheduleDiscovery(config)
        val input = RoundInput(
            snapshot = config.toRoundSnapshot(
                codec = jsonCodec,
                discoveredMcpTools = mcpDiscoveryCoordinator.discoveredToolsSnapshot(config)
            ),
            initialInput = text,
            onEvent = onEvent
        )
        awaitRound(
            state.runReplacingCurrent(
                scope = scope,
                cleanupTools = { roundRunner.cancelAndClearTools(join = true) },
                stopHook = { keepCurrentTurn ->
                    roundRunner.requestStop(input.roundToken, keepCurrentTurn)
                }
            ) {
                roundRunner.run(input)
            }
        )
    }

    override suspend fun stop(keepCurrentTurn: Boolean) {
        state.stopCurrentRound(keepCurrentTurn)
    }

    override suspend fun update(block: SessionConfig.Builder.() -> Unit) {
        val updatedConfig = state.updateConfig(block)
        mcpDiscoveryCoordinator.scheduleDiscovery(updatedConfig, refreshCached = true)
    }

    override suspend fun refreshMcpTools(): McpRefreshResult {
        return mcpDiscoveryCoordinator.refreshEnabledServers(config = thisConfig(), refreshCached = true)
    }

    override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot {
        return mcpDiscoveryCoordinator.getSnapshot(thisConfig())
    }

    override suspend fun getHistory(): List<ChatTurn> {
        return historyKeeper.snapshot().filterNot { it is ChatTurn.System }
    }

    override suspend fun resetConversation() {
        state.cancelCurrentRoundAndRun(
            cleanupTools = { roundRunner.cancelAndClearTools(join = true) }
        ) {
            historyKeeper.clear()
        }
    }

    override suspend fun replaceHistory(history: List<ChatTurn>) {
        state.cancelCurrentRoundAndRun(
            cleanupTools = { roundRunner.cancelAndClearTools(join = true) }
        ) {
            val sanitized = history.filterNot { it is ChatTurn.System }
            historyKeeper.replace(sanitized)
        }
    }

    override suspend fun close() {
        scope.cancel()
        engine.close()
    }

    private suspend fun awaitRound(round: Deferred<Unit>) {
        try {
            round.await()
        } catch (t: Throwable) {
            throw t.unwrapRecoveredCoroutineException()
        }
    }

    private fun Throwable.unwrapRecoveredCoroutineException(): Throwable {
        val original = cause ?: return this
        return if (this::class == original::class && message == original.message) {
            original
        } else {
            this
        }
    }

}
