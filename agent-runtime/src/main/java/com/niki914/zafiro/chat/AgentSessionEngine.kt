package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ThinkingLevel
import com.niki914.okia.message.contextTokens
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.chat.compaction.Compactor
import com.niki914.zafiro.chat.agentic.IngestedImage
import com.niki914.zafiro.chat.agentic.PromptComposer
import com.niki914.zafiro.chat.agentic.PromptComposerInput
import com.niki914.zafiro.chat.agentic.ToolManager
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 独立的 Agent 底层会话引擎。
 *
 * 每个 Agent 实例独立持有一份：
 * - 独立的底层 Okia 会话树与 Hooks（[OkiaSessionAssembler]）；
 * - 独立的工具注册表（[ToolRegistrySynchronizer]）；
 * - 独立的流执行管道与回合状态（[LlmStreamPipeline]）。
 */
class AgentSessionEngine {
    companion object {
        const val LOG_TAG = "niki914_zafiro_AgentSessionEngine"
        const val NO_IDLE_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000
    }

    fun interface OkiaFactory {
        suspend fun create(
            protocol: LlmProtocol,
            restore: SessionSnapshot?,
            config: ResolvedLlmConfig,
        ): Okia
    }

    val tenantId: String = UUID.randomUUID().toString()

    var agentControlProvider: (() -> AgentControl?)? = null

    private var runtimeState: RuntimeState? = null

    private val imageManager = ImageIngestionManager()
    private val toolSynchronizer = ToolRegistrySynchronizer(
        tenantId = tenantId,
        agentControlProvider = { agentControlProvider?.invoke() },
    ) { runtimeState?.snapshot?.tools }
    private val prefixComposer = TurnPrefixComposer()
    internal val sessionAssembler = OkiaSessionAssembler(
        toolRegistry = toolSynchronizer.toolRegistry,
        imageLoader = imageManager.imageLoader,
        ensureImageSaver = imageManager::ensureImageSaver,
    )
    internal val streamPipeline = LlmStreamPipeline()

    private val promptComposer = PromptComposer()
    private val toolManager = ToolManager()
    private val mcpRefreshScheduler =
        McpRefreshScheduler(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    internal val toolRegistry: ToolRegistry get() = toolSynchronizer.toolRegistry

    var compactor: Compactor = Compactor(
        summaryGenerator = { systemPrompt, userPrompt ->
            generateSummary(systemPrompt, userPrompt)
        }
    )

    internal var okia: Okia?
        get() = sessionAssembler.activeSession
        set(value) {
            sessionAssembler.activeSession = value
        }

    internal var okiaFactory: OkiaFactory
        get() = sessionAssembler.okiaFactory
        set(value) {
            sessionAssembler.okiaFactory = value
        }

    val currentConversation: StateFlow<Conversation?> get() = sessionAssembler.currentConversation
    val keepScreenOn: StateFlow<Boolean> get() = streamPipeline.turnActive

    /** 当前生效配置的上下文窗口；[refresh] 时随配置一起落。读者：`AgentImpl.contextUsage` 的分母。 */
    private val contextWindowFlow = MutableStateFlow(DEFAULT_CONTEXT_WINDOW_TOKENS)
    val contextWindowTokens: StateFlow<Long> = contextWindowFlow.asStateFlow()

    @Volatile
    var boundConfigId: String = ""
        private set

    fun bindConfig(configId: String) {
        boundConfigId = configId
    }

    internal fun resetForTest() {
        sessionAssembler.resetForTest()
        runtimeState = null
        toolSynchronizer.reset()
        mcpRefreshScheduler.reset()
        prefixComposer.reset()
        contextWindowFlow.value = DEFAULT_CONTEXT_WINDOW_TOKENS
        boundConfigId = ""
        compactor = Compactor(
            summaryGenerator = { systemPrompt, userPrompt ->
                generateSummary(systemPrompt, userPrompt)
            }
        )
    }

    private val refreshMutex = Mutex()

    /**
     * 预热底层 Okia 会话与 MCP 工具发现。
     * 幂等调用：无可用会话时触发首轮 refresh；已有会话时不做多余重装配。
     */
    suspend fun warmup() {
        if (runtimeState == null) {
            runCatchingCancellable {
                refresh()
            }.onFailure { throwable ->
                Logger.d(LOG_TAG, "warmup skipped or failed: ${throwable.message}")
            }
        }
    }

    suspend fun refresh(restore: SessionSnapshot? = null): LlmRuntimeSnapshot = refreshMutex.withLock {
        val refreshStartedAtMs = System.currentTimeMillis()
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val llmConfig = gateway.readLlmConfig(configId = boundConfigId.takeIf { it.isNotBlank() })
        if (restore == null) {
            sessionAssembler.validateLlmConfig(llmConfig)
        }
        contextWindowFlow.value = llmConfig.contextWindowTokens
        compactor.thresholdRatio = llmConfig.compactionThresholdPercent / 100f
        Logger.i(
            LOG_TAG,
            "config read provider=${llmConfig.provider} model=${llmConfig.model} " +
                    "maxTokens=${llmConfig.maxTokens} contextWindow=${llmConfig.contextWindowTokens} " +
                    "hasApiKey=${llmConfig.apiKey.isNotBlank()} hasProxy=${llmConfig.proxy.isNotBlank()}"
        )
        val protocol = LlmProtocol.fromWire(llmConfig.protocol)
        val runtimeMcpServers = runCatchingCancellable {
            gateway.listMcpServers()
        }.onFailure {
            Logger.w(LOG_TAG, "listMcpServers failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val customPyTools = runCatchingCancellable {
            gateway.listCustomPyTools()
        }.onFailure {
            Logger.w(LOG_TAG, "listCustomPyTools failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val builtinSettings = runCatchingCancellable {
            gateway.listBuiltinToolSettings()
        }.onFailure {
            Logger.w(LOG_TAG, "listBuiltinToolSettings failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val enabledSkills = gateway.listEnabledSkills()
        val resolvedTools = toolManager.resolve(
            customPyTools = customPyTools,
            mcpServers = runtimeMcpServers,
            builtinSettings = builtinSettings,
        )
        Logger.i(
            LOG_TAG,
            "tools resolved builtin=${resolvedTools.builtinTools.size} " +
                    "py=${resolvedTools.customPyTools.size} " +
                    "mcpServers=${resolvedTools.mcpServers.size}"
        )
        val configWithoutRuntimePrompt = ResolvedLlmConfig(
            endpoint = llmConfig.endpoint,
            apiKey = llmConfig.apiKey,
            model = llmConfig.model,
            baseSystemPrompt = llmConfig.prompt,
            finalSystemPrompt = llmConfig.prompt,
            proxy = llmConfig.proxy,
            supportsImages = llmConfig.supportsImages,
            idleTimeoutSeconds = llmConfig.idleTimeoutSeconds,
            retryMaxAttempts = llmConfig.retryMaxAttempts,
            maxTokens = llmConfig.maxTokens,
            thinkingLevel = llmConfig.thinkingLevel.takeIf(String::isNotBlank)
                ?.let(ThinkingLevel::fromWire),
            repetitionSensitivity = llmConfig.repetitionSensitivity,
        )

        val previousSession = runtimeState?.okia
        val activeSession = sessionAssembler.obtainSession(
            protocol = protocol,
            config = configWithoutRuntimePrompt,
            restore = restore,
            forceNew = restore != null,
        )
        activeSession.update {
            endpoint = configWithoutRuntimePrompt.endpoint.ifBlank {
                sessionAssembler.protocolDefaultEndpointFallback(protocol)
            }
            apiKey = configWithoutRuntimePrompt.apiKey
            model = configWithoutRuntimePrompt.model
            idleTimeoutSeconds = configWithoutRuntimePrompt.idleTimeoutSeconds
                ?: NO_IDLE_TIMEOUT_SECONDS
            retryPolicy = RetryPolicy(maxAttempts = configWithoutRuntimePrompt.retryMaxAttempts)
            maxTokens = configWithoutRuntimePrompt.maxTokens
            thinkingLevel = configWithoutRuntimePrompt.thinkingLevel
            repetitionConfig = configWithoutRuntimePrompt.repetitionSensitivity.toRepetitionConfig()
            proxy = configWithoutRuntimePrompt.proxy
            supportsImages =
                sessionAssembler.isImageSupported(configWithoutRuntimePrompt.supportsImages)
            mcpServers = toolSynchronizer.toOkiaMcpServers(resolvedTools.mcpServers)
        }
        toolSynchronizer.syncLocalTools(resolvedTools)
        val mcpSignature = toolSynchronizer.mcpServersSignature(resolvedTools.mcpServers)
        mcpRefreshScheduler.schedule(
            activeSession,
            mcpSignature,
            force = activeSession !== previousSession || restore != null,
        )
        val prompt = promptComposer.compose(
            PromptComposerInput(
                additionalInstructions = llmConfig.prompt,
                memoryItems = PromptComposer.buildMemoryItems(llmConfig),
                tools = resolvedTools,
                enabledSkills = enabledSkills,
                sandboxPaths = imageManager.sandboxPaths(),
            )
        )
        val finalConfig =
            configWithoutRuntimePrompt.copy(finalSystemPrompt = prompt.finalSystemPrompt)

        return@withLock LlmRuntimeSnapshot(finalConfig, resolvedTools, prompt).also { snapshot ->
            runtimeState = RuntimeState(
                snapshot = snapshot,
                okia = activeSession,
                sessionProtocol = protocol,
            )
            Logger.i(
                LOG_TAG,
                "refresh done elapsedMs=${System.currentTimeMillis() - refreshStartedAtMs} " +
                        "model=${snapshot.config.model}"
            )
        }
    }

    suspend fun snapshot(): LlmRuntimeSnapshot? = runtimeState?.snapshot

    /**
     * 确保存在一个可用会话实例（无则建空实例）并返回其树 id。
     */
    suspend fun ensureSession(): String {
        if (okia == null) {
            refresh()
        }
        return okia?.conversation?.value?.id
            ?: error("session not available")
    }

    /**
     * 恢复会话：以快照重建实例。
     */
    suspend fun openSession(restore: SessionSnapshot) {
        val startedAtMs = System.currentTimeMillis()
        prefixComposer.reset()
        Logger.i(
            LOG_TAG,
            "open session id=${restore.id} entries=${restore.entries.size} started"
        )
        refresh(restore = restore)
        Logger.i(
            LOG_TAG,
            "open session done id=${restore.id} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    fun stream(
        query: String,
        images: List<ContentBlock.Image> = emptyList(),
        files: List<FileRef> = emptyList(),
    ): Flow<LlmStreamEvent> = channelFlow {
        try {
            val state = try {
                refresh()
                runtimeState
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                runtimeState ?: run {
                    val code = throwable.toUserErrorCode()
                    val message = throwable.message?.trim()?.ifEmpty { null }
                    Logger.e(
                        LOG_TAG,
                        "refresh failed errorType=${throwable.eventTypeName()} message=$message"
                    )
                    send(
                        LlmStreamEvent.Error(
                            message = message,
                            throwable = throwable,
                            code = code,
                        )
                    )
                    return@channelFlow
                }
            }
            if (state == null) {
                send(LlmStreamEvent.Error(message = null, code = null))
                return@channelFlow
            }
            Logger.i(
                LOG_TAG,
                "refresh ok model=${state.snapshot.config.model} " +
                        "builtin=${state.snapshot.tools.builtinTools.size} " +
                        "py=${state.snapshot.tools.customPyTools.size} " +
                        "mcp=${state.snapshot.tools.mcpServers.size}"
            )

            val notifications = TerminalSessionPool.drainPendingNotifications()
            val mcpNotice = prefixComposer.mcpNotice(state.okia)
            val injection = prefixComposer.buildInjectionPrefixes(files, mcpNotice, notifications)
            if (injection != null) {
                Logger.i(
                    LOG_TAG,
                    "prefixes injected files=${files.size} mcp=${mcpNotice != null} " +
                            "notifications=${notifications.size} " +
                            "chars=${injection.length}"
                )
            }
            val effectiveQuery = if (injection != null) {
                injection + "\n\n" + query
            } else {
                query
            }

            val currentSession = state.okia
            val contextTokens = contextTokensOf(currentSession.conversation.value) ?: 0L
            val contextWindow = contextWindowTokens.value
            compactor.compact(
                session = currentSession,
                force = false,
                contextTokens = contextTokens,
                contextWindow = contextWindow,
            )

            streamPipeline.execute(
                session = state.okia,
                query = effectiveQuery,
                images = images,
                systemPrompt = state.snapshot.config.finalSystemPrompt,
                channel = this,
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.e(
                LOG_TAG,
                "stream error stage=outer code=${throwable.toUserErrorCode()} " +
                        "errorType=${throwable.eventTypeName()} " +
                        "message=${throwable.message}"
            )
            send(
                LlmStreamEvent.Error(
                    message = throwable.message?.trim()?.ifEmpty { null },
                    throwable = throwable,
                    code = throwable.toUserErrorCode(),
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun closeSession() {
        Logger.i(LOG_TAG, "close session requested tenantId=$tenantId")
        TerminalSessionPool.closeTenantSessions(tenantId)
        sessionAssembler.closeSession()
        prefixComposer.reset()
        runtimeState = null
        boundConfigId = ""
        Logger.i(LOG_TAG, "close session done")
    }

    suspend fun resetConversation() {
        Logger.i(LOG_TAG, "reset conversation requested")
        sessionAssembler.resetConversation()
        prefixComposer.reset()
        runtimeState = null
        boundConfigId = ""
        Logger.i(LOG_TAG, "reset conversation done")
    }

    suspend fun stopCurrentRound() {
        Logger.i(LOG_TAG, "stop round requested")
        sessionAssembler.stopCurrentRound()
        Logger.i(LOG_TAG, "stop round done")
    }

    suspend fun ingestUserImage(uriString: String): IngestedImage? =
        imageManager.ingestUserImage(uriString)

    suspend fun compact(force: Boolean = false): Boolean {
        val session = okia ?: run {
            refresh()
            okia
        } ?: return false
        val state = runtimeState ?: run {
            refresh()
            runtimeState
        } ?: return false
        val contextTokens = contextTokensOf(session.conversation.value) ?: 0L
        val contextWindow = contextWindowTokens.value
        return compactor.compact(
            session = session,
            force = force,
            contextTokens = contextTokens,
            contextWindow = contextWindow,
        )
    }

    private suspend fun generateSummary(systemPrompt: String, userPrompt: String): String {
        val state = runtimeState ?: run {
            refresh()
            runtimeState
        } ?: return ""
        val protocol = state.sessionProtocol ?: LlmProtocol.Default
        return sessionAssembler.generateSummary(
            protocol = protocol,
            config = state.snapshot.config,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    private fun contextTokensOf(conversation: Conversation?): Long? {
        conversation?.live?.usage?.contextTokens?.let { return it }
        return conversation?.history?.asReversed()?.firstNotNullOfOrNull { entry ->
            (entry.message as? Message.Assistant)?.message?.usage?.contextTokens
        }
    }

    internal fun validateLlmConfig(config: RuntimeLlmConfig) =
        sessionAssembler.validateLlmConfig(config)

    private data class RuntimeState(
        val snapshot: LlmRuntimeSnapshot,
        val okia: Okia,
        val sessionProtocol: LlmProtocol?,
    )
}
