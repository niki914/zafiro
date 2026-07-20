package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.PromptComposer
import com.niki914.nexus.agentic.chat.agentic.PromptComposerInput
import com.niki914.nexus.agentic.chat.agentic.SessionToolBinder
import com.niki914.nexus.agentic.chat.agentic.ToolCallDispatcher
import com.niki914.nexus.agentic.chat.agentic.ToolManager
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.mcp.McpDiscoveryCacheStore
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.stream.LlmStreamEventMapper
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.model.LlmApiType
import com.niki914.nexus.h.util.LockState
import com.niki914.nexus.h.xevent.XEvent
import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.ToolCallKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

object LLMController {
    internal const val CONFIG_REQUIRED_MESSAGE = "请先填写配置"
    private const val DEFAULT_USER_ERROR_MESSAGE = "请求失败，请重试"

    private val promptComposer =
        PromptComposer()
    private val toolManager =
        ToolManager()
    private val mcpCacheStore =
        McpDiscoveryCacheStore()
    private val toolCallDispatcher =
        ToolCallDispatcher { runtimeState?.snapshot?.tools }

    private var runtimeState: RuntimeState? = null
    private var session: Session? = null
    private var sessionApiType: LlmApiType? = null
    private var lastMcpServersFingerprint: String? = null

    suspend fun refresh(): LlmRuntimeSnapshot {
        val previousSnapshot = runtimeState?.snapshot
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val llmConfig = gateway.readLlmConfig()
        validateLlmConfig(llmConfig)
        val apiType = LlmApiType.fromProvider(llmConfig.provider)
        val mcpServers = gateway.listMcpServers()
        val customTools = gateway.listCustomTools()
        val builtinSettings = gateway.listBuiltinToolSettings()
        val enabledSkills = gateway.listEnabledSkills()
        val resolvedTools = toolManager.resolve(
            customTools = customTools,
            mcpServers = mcpServers,
            builtinSettings = builtinSettings,
            mcpCachedTools = mcpServers.associate { server ->
                server.name to gateway.listCachedTools(server)
            },
        )
        val configWithoutRuntimePrompt = ResolvedLlmConfig(
            endpoint = llmConfig.endpoint,
            apiKey = llmConfig.apiKey,
            model = llmConfig.model,
            baseSystemPrompt = llmConfig.prompt,
            finalSystemPrompt = llmConfig.prompt,
            proxy = llmConfig.proxy,
        )
        val isNewSession = session == null || sessionApiType != apiType
        val currentMcpServersFingerprint = gateway.fingerprintMcpServers()
        val activeSession = obtainSession(apiType)
        activeSession.update {
            applyRuntimeConfig(
                config = configWithoutRuntimePrompt,
                tools = resolvedTools,
                previousTools = previousSnapshot?.tools,
            )
        }
        val shouldRefreshMcp = resolvedTools.mcpServers.isNotEmpty() &&
                (isNewSession || currentMcpServersFingerprint != lastMcpServersFingerprint)
        if (shouldRefreshMcp) {
            var refreshSucceeded = false
            try {
                val refreshResult = activeSession.refreshMcpTools()
                refreshSucceeded = refreshResult.failedServers.isEmpty()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
            }
            lastMcpServersFingerprint = if (refreshSucceeded) {
                currentMcpServersFingerprint
            } else {
                null
            }
        } else {
            lastMcpServersFingerprint = currentMcpServersFingerprint
        }

        val mcpSnapshot = activeSession.getMcpDiscoverySnapshot()
        val prompt = promptComposer.compose(
            PromptComposerInput(
                additionalInstructions = llmConfig.prompt,
                memoryItems = buildMemoryItems(llmConfig),
                tools = resolvedTools,
                mcpDiscoverySnapshot = mcpSnapshot,
                enabledSkills = enabledSkills,
            )
        )
        val finalConfig = configWithoutRuntimePrompt.copy(finalSystemPrompt = prompt.finalSystemPrompt)
        activeSession.update {
            applyRuntimeConfig(
                config = finalConfig,
                tools = resolvedTools,
                previousTools = previousSnapshot?.tools,
            )
        }

        return LlmRuntimeSnapshot(finalConfig, resolvedTools, prompt).also { snapshot ->
            runtimeState = RuntimeState(snapshot = snapshot, session = activeSession)
        }
    }

    suspend fun refreshFromHookContext(): LlmRuntimeSnapshot = refresh()

    suspend fun snapshot(): LlmRuntimeSnapshot? = runtimeState?.snapshot

    suspend fun getHistory(): List<ChatTurn> {
        return session?.getHistory().orEmpty()
    }

    suspend fun replaceHistory(history: List<ChatTurn>) {
        refresh()
        runtimeState?.session?.replaceHistory(history)
    }

    fun stream(query: String): Flow<LlmStreamEvent> = channelFlow {
        val state = try {
            refresh()
            runtimeState
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            runtimeState ?: run {
                val message = throwable.toUserErrorMessage()
                XEvent.llmError(
                    fields = mapOf(
                        "stage" to "refresh",
                        "errorType" to throwable.eventTypeName(),
                        "message" to message
                    )
                )
                send(
                    LlmStreamEvent.Error(
                        message = message,
                        throwable = throwable,
                        code = throwable.toUserErrorCode(),
                    )
                )
                return@channelFlow
            }
        }
        if (state == null) {
            send(LlmStreamEvent.Error(DEFAULT_USER_ERROR_MESSAGE))
            return@channelFlow
        }

        val accumulator = StringBuilder()
        val startedAtMs = System.currentTimeMillis()
        var streamErrorReported = false
        val sink: SendChannel<LlmStreamEvent> = this
        try {
            XEvent.llmRoundStarted(
                fields = mapOf(
                    "queryLength" to query.length,
                    "isUnlocked" to LockState.isUnlocked()
                )
            )
            state.session.send(query).collect { event ->
                val mapped = LlmStreamEventMapper.map(event, accumulator, startedAtMs)
                mapped?.let {
                    if (it is LlmStreamEvent.Error && !streamErrorReported) {
                        streamErrorReported = true
                        XEvent.llmError(
                            fields = mapOf(
                                "stage" to "session_event",
                                "errorType" to (it.throwable?.eventTypeName() ?: "SessionEvent"),
                                "message" to it.message
                            )
                        )
                    }
                    sink.send(it)
                }
            }
            if (!streamErrorReported) {
                XEvent.llmRoundCompleted(
                    fields = mapOf(
                        "textLength" to accumulator.length,
                        "elapsedMs" to (System.currentTimeMillis() - startedAtMs)
                    )
                )
            }
            AccessibilityController.onTurnEnd()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            AccessibilityController.onTurnEnd()
            XEvent.llmError(
                fields = mapOf(
                    "stage" to "send",
                    "errorType" to throwable.eventTypeName(),
                    "message" to throwable.toUserErrorMessage()
                )
            )
            send(
                LlmStreamEvent.Error(
                    message = throwable.toUserErrorMessage(),
                    throwable = throwable,
                    code = throwable.toUserErrorCode(),
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun resetConversation() {
        session?.resetConversation()
        TerminalSessionPool.closeAll()
    }

    suspend fun stopCurrentRound(keepCurrentTurn: Boolean = false) {
        session?.stop(keepCurrentTurn = keepCurrentTurn)
    }

    private suspend fun obtainSession(apiType: LlmApiType): Session {
        session?.takeIf { sessionApiType == apiType }?.let { return it }
        session?.close()
        lastMcpServersFingerprint = null
        return openSession(apiType).also {
            session = it
            sessionApiType = apiType
        }
    }

    private suspend fun openSession(apiType: LlmApiType): Session {
        val configBlock: SessionConfig.Builder.() -> Unit = {
            mcpHooks {
                onToolsDiscovered = mcpCacheStore::onToolsDiscovered
            }
            hooks {
                when (kind) {
                    ToolCallKind.Local -> {
                        ok(
                            toolCallDispatcher.executeLocalTool(
                                name = name,
                                argumentsJson = argumentsJson,
                            )
                        )
                    }

                    is ToolCallKind.Mcp -> delegate()
                }
            }
            llmIdleTimeoutSeconds = 50
        }
        return when (apiType) {
            LlmApiType.Anthropic -> Session.open<SessionProtocols.Anthropic>(configBlock)
            LlmApiType.DeepSeek -> Session.open<SessionProtocols.DeepSeek>(configBlock)
            else -> Session.open<SessionProtocols.OpenAI>(configBlock)
        }
    }

    private fun buildMemoryItems(config: LlmConfig): List<String> {
        val memories = config.memories.map(String::trim).filter(String::isNotBlank)
        if (memories.isNotEmpty()) {
            return memories
        }
        return listOfNotNull(config.memoryPrompt.trim().takeIf { it.isNotBlank() })
    }

    internal fun validateLlmConfig(config: LlmConfig) {
        if (config.endpoint.isBlank() || config.model.isBlank()) {
            throw LlmConfigRequiredException()
        }
    }

    private fun Throwable.toUserErrorMessage(): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_USER_ERROR_MESSAGE
    }

    private fun Throwable.toUserErrorCode(): LlmErrorCode? {
        return when (this) {
            is LlmConfigRequiredException -> LlmErrorCode.ConfigRequired
            else -> null
        }
    }

    private fun Throwable.eventTypeName(): String = this::class.simpleName ?: "Throwable"

    private fun SessionConfig.Builder.applyRuntimeConfig(
        config: ResolvedLlmConfig,
        tools: ResolvedTools,
        previousTools: ResolvedTools?,
    ) {
        endpoint = config.endpoint
        apiKey = config.apiKey
        model = config.model
        systemPrompt = config.finalSystemPrompt

        SessionToolBinder.run { bindTools(tools = tools, previousTools = previousTools) }
    }

    private data class RuntimeState(val snapshot: LlmRuntimeSnapshot, val session: Session)

    private class LlmConfigRequiredException : IllegalStateException(CONFIG_REQUIRED_MESSAGE)
}
