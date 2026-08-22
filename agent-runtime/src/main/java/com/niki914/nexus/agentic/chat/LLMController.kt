package com.niki914.nexus.agentic.chat

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.agentic.PromptComposer
import com.niki914.nexus.agentic.chat.agentic.PromptComposerInput
import com.niki914.nexus.agentic.chat.agentic.LocalToolExecutor
import com.niki914.nexus.agentic.chat.agentic.ToolManager
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.stream.LlmStreamEventMapper
import com.niki914.nexus.agentic.runtime.R
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.model.LlmApiType
import com.niki914.nexus.xposed.api.util.LockState
import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.OpenAIChatCompletionCompat
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

/**
 * Nexus 的 LLM 回合执行入口。OKIA 接入 T1 重写：
 * - 运行时从 Kai 切到 Okia（一次对话一个实例：换会话/重建 = close + open(restore)）
 * - 终态以 send 返回值（TurnResult）承载，事件流只承担中间过程
 * - 工具注册/执行/MCP 发现留给 T2：T1/T2 期间未注册工具的调用已不死循环（T2c：
 *   未知工具 = Failure 结果回喂，回合继续，模型可自纠）；kill-then-stop 已下沉到
 *   Hooks.beforeStop（OKIA stop() 先杀资源再取消 job）
 * - T3：持久化会话生命周期——getHistory/replaceHistory ChatTurn 桥接已删，
 *   由 ensureSession()（新会话惰性建实例，树 id = Room id）+ openSession(restore)
 *   （恢复/切会话）+ currentConversation（统一快照流，持久化器消息级增量落盘）替代
 */
object LLMController {
    private const val LOG_TAG = "niki914_nexus_LLMController"
    internal const val CONFIG_REQUIRED_MESSAGE = "请先填写配置" // <--- TODO res
    private const val LLM_IDLE_TIMEOUT_SECONDS = 30L

    private val promptComposer =
        PromptComposer()
    private val toolManager =
        ToolManager()

    // T2a：OKIA 工具注册表（host 持有、注入经 OkiaConfig.toolRegistry；
    // 实例重建共享同一 registry）。本地工具在 refresh 时全量同步；
    // MCP 工具由 T2b McpDiscovery 注册进同一 registry。
    internal val toolRegistry: ToolRegistry = DefaultToolRegistry()

    // 回合内创建的自定义工具（CreateCustomTool 成功回调，D20）：
    // 持久化尚未被下一次 refresh 读取前的执行兜底 + 回合内注册数据源。
    private val inlineCustomTools = mutableMapOf<String, LocalTool.Custom>()

    private val localToolExecutor = LocalToolExecutor(
        currentTools = { runtimeState?.snapshot?.tools },
        inlineCustomTools = inlineCustomTools,
        onCustomToolCreated = { tool -> registerCustomToolNow(tool) },
    )

    private var runtimeState: RuntimeState? = null
    private var okia: Okia? = null
    private var sessionApiType: LlmApiType? = null

    // T3：当前会话快照统一流（持久化器观察它做消息级增量落盘，D3-8）。
    // OKIA conversation StateFlow 是每实例的（切会话 = 换实例 = 换引用），
    // 这里转发当前实例的流，实例切换时重发射，观察者对实例切换透明。
    private val conversationForwardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationFlow = MutableStateFlow<Conversation?>(null)
    private var sessionForwardJob: Job? = null

    /** 当前会话树快照（null = 无会话实例）。实例切换自动重发射。 */
    val currentConversation: StateFlow<Conversation?> get() = conversationFlow

    // T2b MCP 发现（D-T2B-3 方案 B）：后台协程刷新，不阻塞 LLM 回合。
    // - 启动 eager：首次 refresh（签名 null ≠ 配置）触发一次后台刷新
    // - turn 前标脏：refresh() 比较服务器配置签名（name/url/headers/enabled
    //   序列化），变化才起后台刷新；无变化不刷（零网络开销）
    // - inFlight 防重：同一时刻至多一个后台刷新（失败也更新签名防风暴，
    //   用户改配置→签名变→重刷）
    // - 回合中刷新撞活跃回合（OKIA refreshMcpTools 抛异常）→ catch 记日志
    // - 已知限制：OKIA refreshMcpTools 与 send 共用 RealOkia mutex，后台刷新
    //   持锁期间发问会短排队——见 ISSUES_okia-integration.md OKIA-1（待提）
    private val mcpRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastRefreshedMcpSignature: String? = null
    private val mcpRefreshInFlight = AtomicBoolean(false)

    // 测试注入点：T1 单测经 Okia.open(dependencies) 装配 fake loop/mapper。 <--- TODO Workaround???
    internal var okiaFactory: OkiaFactory = OkiaFactory { apiType, restore, config ->
        openOkiaWithDefaultProtocol(apiType, restore, config)
    }

    internal fun resetForTest() {
        kotlinx.coroutines.runBlocking { okia?.close() }
        okia = null
        sessionApiType = null
        runtimeState = null
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        conversationFlow.value = null
        toolRegistry.snapshot().forEach { toolRegistry.remove(it.descriptor.wireName) }
        inlineCustomTools.clear()
        lastRefreshedMcpSignature = null
        mcpRefreshInFlight.set(false)
        okiaFactory = OkiaFactory { apiType, restore, config ->
            openOkiaWithDefaultProtocol(apiType, restore, config)
        }
    }

    internal fun interface OkiaFactory {
        suspend fun create(
            apiType: LlmApiType,
            restore: SessionSnapshot?,
            config: ResolvedLlmConfig,
        ): Okia
    }

    suspend fun refresh(): LlmRuntimeSnapshot {
        val previousSnapshot = runtimeState?.snapshot
        val refreshStartedAtMs = System.currentTimeMillis()
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val llmConfig = gateway.readLlmConfig()
        validateLlmConfig(llmConfig)
        Logger.i(
            LOG_TAG,
            "config read provider=${llmConfig.provider} model=${llmConfig.model} " +
                "hasApiKey=${llmConfig.apiKey.isNotBlank()} hasProxy=${llmConfig.proxy.isNotBlank()}"
        )
        val apiType = LlmApiType.fromProvider(llmConfig.provider)
        val runtimeMcpServers = gateway.listMcpServers()
        val customTools = gateway.listCustomTools()
        val builtinSettings = gateway.listBuiltinToolSettings()
        val enabledSkills = gateway.listEnabledSkills()
        val resolvedTools = toolManager.resolve(
            customTools = customTools,
            mcpServers = runtimeMcpServers,
            builtinSettings = builtinSettings,
        )
        Logger.i(
            LOG_TAG,
            "tools resolved builtin=${resolvedTools.builtinTools.size} " +
                "custom=${resolvedTools.customTools.size} " +
                "mcpServers=${resolvedTools.mcpServers.size}"
        )
        val configWithoutRuntimePrompt = ResolvedLlmConfig(
            endpoint = llmConfig.endpoint,
            apiKey = llmConfig.apiKey,
            model = llmConfig.model,
            baseSystemPrompt = llmConfig.prompt,
            finalSystemPrompt = llmConfig.prompt,
            proxy = llmConfig.proxy,
        )
        // T1：会话按协议类型重建；协议切换 = close + 新实例（D1/§5.1）
        val activeSession = obtainSession(apiType, configWithoutRuntimePrompt)
        activeSession.update {
            endpoint = configWithoutRuntimePrompt.endpoint
            apiKey = configWithoutRuntimePrompt.apiKey
            model = configWithoutRuntimePrompt.model
            // T2b：MCP 服务器配置进 OKIA（McpDiscovery 发现后注册进同一 toolRegistry）
            mcpServers = toOkiaMcpServers(resolvedTools.mcpServers)
        }
        // T2a：本地工具注册（enabled 集合全量重建；inline 回合内工具由
        // registerCustomToolNow 注册，随下次 refresh 由持久化版本接管）
        syncLocalTools(resolvedTools)
        // T2b：MCP 发现（方案 B，D-T2B-3）：签名变化才起后台刷新，不 await
        // （不阻塞回合）；初始化时签名 null → 首次天然触发（启动 eager）
        val mcpSignature = mcpServersSignature(resolvedTools.mcpServers)
        scheduleMcpRefresh(activeSession, mcpSignature)
        // 工具描述进入提示词（技能/记忆段依赖它）；MCP 工具段已删除
        // （D-T2B-2：线缆名 mcp__server__tool 已表达服务器归属）
        val prompt = promptComposer.compose(
            PromptComposerInput(
                additionalInstructions = llmConfig.prompt,
                memoryItems = buildMemoryItems(llmConfig),
                tools = resolvedTools,
                enabledSkills = enabledSkills,
            )
        )
        val finalConfig =
            configWithoutRuntimePrompt.copy(finalSystemPrompt = prompt.finalSystemPrompt)

        return LlmRuntimeSnapshot(finalConfig, resolvedTools, prompt).also { snapshot ->
            runtimeState = RuntimeState(
                snapshot = snapshot,
                okia = activeSession,
                sessionApiType = apiType,
            )
            Logger.i(
                LOG_TAG,
                "refresh done elapsedMs=${System.currentTimeMillis() - refreshStartedAtMs} " +
                    "model=${snapshot.config.model}"
            )
        }
    }

    suspend fun refreshFromHookContext(): LlmRuntimeSnapshot = refresh()

    suspend fun snapshot(): LlmRuntimeSnapshot? = runtimeState?.snapshot

    /**
     * 确保存在一个可用会话实例（无则建空实例）并返回其树 id（T3）。
     * 树 id == Room 会话 id：HomeChatState 拿它创建 Room 会话，
     * 之后 open(restore) 恢复时树 id 从快照 id 取（对齐）。
     */
    suspend fun ensureSession(): String {
        if (okia == null) {
            refresh()
        }
        return okia?.conversation?.value?.id
            ?: error("session not available")
    }

    /**
     * 恢复会话（T3，替代 replaceHistory）：关闭当前实例，以 Room 读出的
     * 树快照重建实例（close + open(restore)）。调用方负责先 stop（D3-9）。
     */
    suspend fun openSession(restore: SessionSnapshot) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(
            LOG_TAG,
            "open session id=${restore.id} entries=${restore.entries.size} started"
        )
        if (runtimeState == null) {
            refresh()
        }
        val current = runtimeState ?: return
        val newSession = obtainSession(
            apiType = current.sessionApiType,
            config = current.snapshot.config,
            restore = restore,
            forceNew = true,
        )
        runtimeState = current.copy(okia = newSession)
        Logger.i(
            LOG_TAG,
            "open session done id=${restore.id} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    /**
     * 当前会话树投影消息列表（fork/regen 的 User 定位用，T3）。
     */
    suspend fun historySnapshot(): List<Message> =
        okia?.conversation?.value?.history?.map { it.message }.orEmpty()

    fun stream(query: String, context: Context): Flow<LlmStreamEvent> = channelFlow {
        val defaultErrorMessage = context.getString(R.string.error_llm_request_failed)
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
                    Logger.e(LOG_TAG, "refresh failed errorType=${throwable.eventTypeName()} message=$message")
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
            Logger.i(
                LOG_TAG,
                "refresh ok model=${state.snapshot.config.model} " +
                    "builtin=${state.snapshot.tools.builtinTools.size} " +
                    "custom=${state.snapshot.tools.customTools.size} " +
                    "mcp=${state.snapshot.tools.mcpServers.size}"
            )

            val startedAtMs = System.currentTimeMillis()
            var streamErrorReported = false
            var firstFrameLogged = false
            val sink: SendChannel<LlmStreamEvent> = this
            try {
                Logger.i(LOG_TAG, "round started queryLength=${query.length} isUnlocked=${LockState.isUnlocked()}")
                // 异步任务完成通知注入（PRD okia §5.10）：host 侧拼进 send 文本，
                // 不进 hook、不进会话树（通知进树即污染历史）
                val notifications = TerminalSessionPool.drainPendingNotifications()
                val effectiveQuery = if (notifications.isNotEmpty()) {
                    notifications.joinToString("\n\n") + "\n\n" + query
                } else {
                    query
                }
                // 终态以返回值承载（TurnResult）；onEvent 只承担流式中间过程。
                val result = try {
                    state.okia.send(
                        text = effectiveQuery,
                        options = TurnOptions(systemPrompt = state.snapshot.config.finalSystemPrompt),
                    ) { event ->
                        val mapped = LlmStreamEventMapper.map(event, startedAtMs, defaultErrorMessage)
                        mapped?.let {
                            if (!firstFrameLogged && it is LlmStreamEvent.TextDelta) {
                                firstFrameLogged = true
                                Logger.i(
                                    LOG_TAG,
                                    "first frame elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                        "charsPerSecond=${it.charsPerSecond}"
                                )
                            }
                            if (it is LlmStreamEvent.Error && !streamErrorReported) {
                                streamErrorReported = true
                                Logger.e(
                                    LOG_TAG,
                                    "stream error stage=session_event code=${it.code} " +
                                        "errorType=${it.throwable?.eventTypeName() ?: "OkiaEvent"} " +
                                        "message=${it.message} " +
                                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                                )
                            }
                            sink.send(it)
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    // OKIA 失败走 TurnResult 不抛；此处捕获契约违例（并发 send /
                    // closed 等），转错误事件保持 UI 行为（D9）
                    if (!streamErrorReported) {
                        Logger.e(
                            LOG_TAG,
                            "stream error stage=send code=${throwable.toUserErrorCode()} " +
                                "errorType=${throwable.eventTypeName()} " +
                                "message=${throwable.toUserErrorMessage(defaultErrorMessage)} " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                        )
                        send(
                            LlmStreamEvent.Error(
                                message = throwable.toUserErrorMessage(defaultErrorMessage),
                                throwable = throwable,
                                code = throwable.toUserErrorCode(),
                            )
                        )
                    }
                    null
                }
                // 终态兜底：事件流中间过程未覆盖的失败（防御路径，正常事件已含
                // TurnFailed 映射），按返回值补发一条错误事件
                if (result is TurnResult.Failed && !streamErrorReported) {
                    val error = result.error
                    Logger.e(
                        LOG_TAG,
                        "stream failed by TurnResult code=${error.code} " +
                            "message=${error.message} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                    send(
                        LlmStreamEvent.Error(
                            message = error.message.trim().ifEmpty { defaultErrorMessage },
                            throwable = error.cause,
                            code = null,
                        )
                    )
                }
                if (!streamErrorReported) {
                    Logger.i(
                        LOG_TAG,
                        "round completed elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                Logger.e(
                    LOG_TAG,
                    "stream error stage=send code=${throwable.toUserErrorCode()} " +
                        "errorType=${throwable.eventTypeName()} " +
                        "message=${throwable.toUserErrorMessage(defaultErrorMessage)} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
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
        }
    }.flowOn(Dispatchers.IO)

    suspend fun resetConversation() {
        Logger.i(LOG_TAG, "reset conversation requested")
        // 丢弃当前会话实例（T3）：kill 工具资源 + close，不建新实例。
        // 新会话实例由 ensureSession() 在第一次 send 时惰性创建
        // （树 id 与 Room 会话 id 对齐）；kill 动作确保新会话不继承
        // 上一个回合的工具状态（Binder 调用与 exec 立即结束）。
        PyRuntime.kill()
        TerminalSessionPool.closeAll()
        okia?.close()
        okia = null
        sessionApiType = null
        conversationFlow.value = null
        Logger.i(LOG_TAG, "reset conversation done")
    }

    suspend fun stopCurrentRound() {
        Logger.i(LOG_TAG, "stop round requested")
        // OKIA stop() 内建 kill-then-stop：beforeStop hook（杀 py/tty）先于
        // 取消 job 执行，阻塞工具不再吃得协程取消（§5.11）。
        // OKIA 停止不动会话树，下一轮自然承接历史。
        okia?.stop()
        Logger.i(LOG_TAG, "stop round done")
    }

    // ── 会话管理（OKIA 实例生命周期） ──────────────────────────────────────────

    private suspend fun obtainSession(
        apiType: LlmApiType,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot? = null,
        forceNew: Boolean = false,
    ): Okia {
        if (!forceNew && restore == null) {
            okia?.takeIf { sessionApiType == apiType }?.let { return it }
        }
        okia?.close()
        return openSession(apiType, config, restore).also {
            okia = it
            sessionApiType = apiType
            forwardConversation(it)
        }
    }

    /** 转发当前实例的 conversation StateFlow 到统一流（实例切换重发射）。 */
    private fun forwardConversation(session: Okia) {
        sessionForwardJob?.cancel()
        sessionForwardJob = conversationForwardScope.launch {
            session.conversation.collect { conversationFlow.value = it }
        }
    }

    private suspend fun openSession(
        apiType: LlmApiType,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot?,
    ): Okia = okiaFactory.create(apiType, restore, config)

    private suspend fun openOkiaWithDefaultProtocol(
        apiType: LlmApiType,
        restore: SessionSnapshot?,
        config: ResolvedLlmConfig,
    ): Okia {
        val protocol = when (apiType) {
            LlmApiType.DeepSeek -> OpenAIChatCompletionProtocol()
            LlmApiType.Anthropic -> AnthropicMessagesProtocol()
            LlmApiType.OpenAI -> OpenAIChatCompletionProtocol(Json, OpenAIChatCompletionCompat())
        }
        return Okia.open(protocol, restore) {
            endpoint = config.endpoint
            apiKey = config.apiKey
            model = config.model
            hooks += killToolResourcesHook
            idleTimeoutSeconds = LLM_IDLE_TIMEOUT_SECONDS
            toolRegistry = this@LLMController.toolRegistry
        }
    }

    // ── T2a 工具注册 ────────────────────────────────────────────────────────

    /**
     * 全量重建本地工具注册：registry 中所有 Local 工具先移除（含 inline 的，
     * create_custom_tool 保存成功后本轮会以持久化版本重新注册），再注册当前
     * resolved 的 enabled 工具。wireName 为 registry 键（默认
     * ToolWireName.forLocal(name)），同名覆盖无需特判。
     */
    private fun syncLocalTools(tools: ResolvedTools) {
        toolRegistry.snapshot()
            .map { it.descriptor }
            .filter { it.kind is ToolKind.Local }
            .forEach { toolRegistry.remove(it.wireName) }
        (tools.builtinTools + tools.customTools).forEach { tool ->
            val inputSchemaJson =
                (tool as? LocalTool.Builtin)?.tool?.inputSchemaJson
            toolRegistry.register(
                ToolDescriptor(
                    name = tool.name,
                    description = tool.description,
                    inputSchemaJson = inputSchemaJson,
                    kind = ToolKind.Local,
                ),
                localToolExecutor,
            )
        }
        inlineCustomTools.clear()
    }

    /**
     * CreateCustomTool 成功且 enabled 的回合内注册（D20）：立即注册进
     * registry，当前回合下一轮模型请求即可见（RealAgentLoop 每段现取
     * snapshot）。下次 refresh 以持久化版本重新注册（同名覆盖）。
     */
    private fun registerCustomToolNow(tool: LocalTool.Custom) {
        toolRegistry.register(
            ToolDescriptor(
                name = tool.name,
                description = tool.description,
                inputSchemaJson = null,
                kind = ToolKind.Local,
            ),
            localToolExecutor,
        )
        Logger.i(
            LOG_TAG,
            "custom tool registered in-turn name=${tool.name} enabled=${tool.enabled}"
        )
    }

    // ── T2b MCP 发现时序（方案 B，D-T2B-3，对齐 Codex eager + 标脏刷新） ────

    /**
     * 签名变化才起后台刷新（不 await，不阻塞回合）。
     * 先记签名再 launch：锁住"并发 refresh 只刷一次"（两个回合前 refresh
     * 并发时，第二个看到签名已更新不会重复刷）。inFlight 防重：同一时刻
     * 至多一个后台刷新任务。成功/失败都更新签名——本轮已尝试，防止每轮
     * 重试风暴；用户改配置 → 签名变 → 重新触发。
     * 初始化 eager：首次 refresh 时 lastRefreshedMcpSignature == null ≠ 配置
     * 签名 → 天然触发一次后台刷新（LLMController 初始化预取）。
     */
    private fun scheduleMcpRefresh(session: Okia, signature: String) {
        if (signature == lastRefreshedMcpSignature) return
        lastRefreshedMcpSignature = signature
        if (!mcpRefreshInFlight.compareAndSet(false, true)) return
        mcpRefreshScope.launch {
            val startedAtMs = System.currentTimeMillis()
            try {
                val result: McpRefreshResult = session.refreshMcpTools()
                Logger.i(
                    LOG_TAG,
                    "mcp refresh done elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "refreshed=${result.refreshedServers.joinToString(",") { "\"$it\"" }} " +
                        "failed=${result.failedServers.joinToString(",") { "\"$it\"" }}"
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                // 活跃回合撞 refreshMcpTools（OKIA 契约）→ 下轮签名未变不重试；
                // 连接失败/超时 → 已更新签名，防风暴（用户改配置后重刷）
                Logger.e(
                    LOG_TAG,
                    "mcp refresh failed elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "errorType=${throwable.eventTypeName()} message=${throwable.message}"
                )
            } finally {
                mcpRefreshInFlight.set(false)
            }
        }
    }

    /** 服务器配置签名：对 McpServerDefinition.Http（name/url/headers/enabled）确定性序列化。 */
    private fun mcpServersSignature(servers: List<McpServerDefinition>): String {
        return servers
            .sortedBy { it.name }
            .joinToString(separator = "\n") { server ->
                when (server) {
                    is McpServerDefinition.Http -> {
                        val headers = server.headers
                            .mapKeys { (key, _) -> key.lowercase() }
                            .toSortedMap()
                            .entries
                            .joinToString(separator = "&") { (key, value) -> "$key=$value" }
                        "${server.name}|${server.url}|${server.enabled}|$headers"
                    }
                }
            }
    }

    /** McpServerDefinition.Http → OKIA McpServer（字段一一对应，T2b）。 */
    private fun toOkiaMcpServers(servers: List<McpServerDefinition>): List<McpServer> {
        return servers.mapNotNull { server ->
            when (server) {
                is McpServerDefinition.Http ->
                    McpServer(
                        name = server.name,
                        transport = McpTransport.Http(server.url),
                        headers = server.headers,
                        enabled = server.enabled,
                    )
            }
        }
    }

    // 全局工具资源 kill 钩子：OKIA 停止流程的 kill 步骤（beforeStop 每回合
    // 至多一次，参数为本回合已派发的工具调用，共享资源池不会被误杀）
    private val killToolResourcesHook = object : Hooks {
        override suspend fun beforeStop(calls: List<ContentBlock.ToolCall>) {
            Logger.i(LOG_TAG, "beforeStop killing tool resources dispatchedCalls=${calls.size}")
            // 不先杀，OKIA 的 stop 会 join 等待工具协程直到命令自然结束：
            // - PyRuntime.kill()：python 工具在独立进程，杀进程使 Binder 调用断开
            // - TerminalSessionPool.closeAll()：terminal 工具没有独立进程，
            //   协程取消传播不可靠，关会话使正在执行的 exec 走正常终止路径
            PyRuntime.kill()
            TerminalSessionPool.closeAll()
        }
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────────

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
            // OKIA 并发契约违例（活跃回合中 send）转 TurnConflict，保持 UI 行为
            is IllegalStateException -> LlmErrorCode.TurnConflict
            else -> null
        }
    }

    private fun Throwable.eventTypeName(): String = this::class.simpleName ?: "Throwable"

    private data class RuntimeState(
        val snapshot: LlmRuntimeSnapshot,
        val okia: Okia,
        val sessionApiType: LlmApiType,
    )

    private class LlmConfigRequiredException : IllegalStateException(CONFIG_REQUIRED_MESSAGE)
}