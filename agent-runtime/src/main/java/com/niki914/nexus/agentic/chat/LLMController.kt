package com.niki914.nexus.agentic.chat

import android.content.Context
import com.niki914.nexus.agentic.chat.agentic.PromptComposer
import com.niki914.nexus.agentic.chat.agentic.PromptComposerInput
import com.niki914.nexus.agentic.chat.agentic.SessionToolBinder
import com.niki914.nexus.agentic.chat.agentic.ToolCallDispatcher
import com.niki914.nexus.agentic.chat.agentic.ToolManager
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.mcp.McpDiscoveryCacheStore
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.chat.agentic.stream.LlmStreamEventMapper
import com.niki914.nexus.agentic.runtime.R
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.model.LlmApiType
import com.niki914.nexus.xposed.api.util.LockState
import com.niki914.nexus.xposed.api.xevent.XEvent
import com.niki914.kai.ChatTurn
import com.niki914.kai.Session
import com.niki914.kai.SessionConfig
import com.niki914.kai.SessionProtocols
import com.niki914.kai.ToolCallKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

object LLMController {
    private val turnMutex = Mutex()

    internal const val CONFIG_REQUIRED_MESSAGE = "请先填写配置"
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
        val finalConfig =
            configWithoutRuntimePrompt.copy(finalSystemPrompt = prompt.finalSystemPrompt)
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

    fun stream(query: String, context: Context): Flow<LlmStreamEvent> = channelFlow {
        val defaultErrorMessage = context.getString(R.string.error_llm_request_failed)
        if (!turnMutex.tryLock()) {
            send(LlmStreamEvent.Error("A turn is already active"))
            return@channelFlow
        }
        try {
            val state = try {
                refresh()
                runtimeState
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                runtimeState ?: run {
                    val message = throwable.toUserErrorMessage(defaultErrorMessage)
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
                send(LlmStreamEvent.Error(defaultErrorMessage))
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
                // Inject pending background-task completion notifications
                // into this turn's effective user message.
                val notifications = TerminalSessionPool.drainPendingNotifications()
                val effectiveQuery = if (notifications.isNotEmpty()) {
                    notifications.joinToString("\n\n") + "\n\n" + query
                } else {
                    query
                }
                state.session.send(effectiveQuery).collect { event ->
                    val mapped = LlmStreamEventMapper.map(
                        event,
                        accumulator,
                        startedAtMs,
                        defaultErrorMessage
                    )
                    mapped?.let {
                        if (it is LlmStreamEvent.Error && !streamErrorReported) {
                            streamErrorReported = true
                            XEvent.llmError(
                                fields = mapOf(
                                    "stage" to "session_event",
                                    "errorType" to (it.throwable?.eventTypeName()
                                        ?: "SessionEvent"),
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
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                XEvent.llmError(
                    fields = mapOf(
                        "stage" to "send",
                        "errorType" to throwable.eventTypeName(),
                        "message" to throwable.toUserErrorMessage(defaultErrorMessage)
                    )
                )
                send(
                    LlmStreamEvent.Error(
                        message = throwable.toUserErrorMessage(defaultErrorMessage),
                        throwable = throwable,
                        code = throwable.toUserErrorCode(),
                    )
                )
            }
        } finally {
            AccessibilityController.onTurnEnd()
            turnMutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun resetConversation() {
        // 先杀 python worker / 关 terminal 会话：Binder 调用与 exec 立即结束，
        // 工具协程快速死亡，新会话不继承上一个回合的工具状态
        PyRuntime.kill()
        TerminalSessionPool.closeAll()
        session?.resetConversation()
    }

    suspend fun stopCurrentRound(keepCurrentTurn: Boolean = false) {
        // 终止键语义 = 杀掉仍在运行的工具，必须先杀后 stop：
        // - PyRuntime.kill()：python 工具在独立进程，杀进程使 Binder 调用断开
        // - TerminalSessionPool.closeAll()：terminal 工具没有独立进程，
        //   协程取消传播不可靠（命令进程不随取消终止，exec 的 coroutineScope
        //   会等子协程，且 executeForegroundLocal 取消时会话泄漏）。
        //   关会话 → session.state 变 Closed → 正在执行的 exec 走
        //   SessionTerminated 正常路径返回 → 工具协程立即结束。
        // 不先杀，kai 的 stop 会 join 等待工具协程直到命令自然结束。
        PyRuntime.kill()
        TerminalSessionPool.closeAll()
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

    private fun Throwable.toUserErrorMessage(fallbackMessage: String): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackMessage
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
