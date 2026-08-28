package com.niki914.zafiro.chat

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.PromptComposerInput
import com.niki914.zafiro.chat.agentic.LocalToolExecutor
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.stream.LlmStreamEventMapper
import com.niki914.zafiro.R
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.xposed.api.util.LockState
import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.mcp.McpDiscoveryState
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpServerDiscoverySnapshot
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.OpenAIChatCompletionCompat
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.protocol.OpenAIResponsesProtocol
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.niki914.zafiro.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.zafiro.chat.agentic.PromptComposer
import com.niki914.zafiro.chat.agentic.ToolManager

/**
 * Zafiro 的 LLM 回合执行入口。OKIA 接入 T1 重写：
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

    // 回合内写入的 py 工具（pytools write 成功回调，D20）：
    // 持久化尚未被下一次 refresh 读取前的执行兜底 + 回合内注册数据源。
    private val inlinePyTools = mutableMapOf<String, LocalTool.Py>()

    private val localToolExecutor = LocalToolExecutor(
        currentTools = { runtimeState?.snapshot?.tools },
        inlinePyTools = inlinePyTools,
        onPyToolWritten = { tool -> registerPyToolNow(tool) },
    )

    private var runtimeState: RuntimeState? = null
    private var okia: Okia? = null
    private var sessionProtocol: LlmProtocol? = null

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
    // - 签名三态分离（问题 4 修复）：desired（当前配置想要）/ 已成功 /
    // T2b MCP 发现（D-T2B-3 方案 B）：后台协程刷新，不阻塞 LLM 回合。
    // - 启动 eager：首次 refresh（签名 null ≠ 配置）触发一次后台刷新
    // - turn 前标脏：refresh() 比较服务器配置签名（name/url/headers/enabled
    //   序列化），变化才起后台刷新；无变化不刷（零网络开销）
    // - 调度状态机 + 失败退避收敛在 McpRefreshScheduler（问题 4 修复：
    //   失败/部分失败不记为成功，配置 in-flight 变化不吞）
    // - 已知限制解除：OKIA refreshMcpTools 已移出活跃回合互斥（#125），
    //   后台刷新与 send 不再争锁，回合内刷新不抛异常
    private val mcpRefreshScheduler =
        McpRefreshScheduler(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // MCP 失败注入去重（#switch-refresh 配套）：同一失败片段（服务器集合 +
    // 错误摘要）只注入一次；恢复（无 Failed）即重置，新失败片段重新注入
    @Volatile
    private var mcpFailureSignature: String? = null

    // 测试注入点：T1 单测经 Okia.open(dependencies) 装配 fake loop/mapper。 <--- TODO Workaround???
    internal var okiaFactory: OkiaFactory = OkiaFactory { apiType, restore, config ->
        openOkiaWithDefaultProtocol(apiType, restore, config)
    }

    internal fun resetForTest() {
        kotlinx.coroutines.runBlocking { okia?.close() }
        okia = null
        sessionProtocol = null
        runtimeState = null
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        conversationFlow.value = null
        toolRegistry.snapshot().forEach { toolRegistry.remove(it.descriptor.wireName) }
        inlinePyTools.clear()
        mcpRefreshScheduler.reset()
        mcpFailureSignature = null
        okiaFactory = OkiaFactory { protocol, restore, config ->
            openOkiaWithDefaultProtocol(protocol, restore, config)
        }
    }

    internal fun interface OkiaFactory {
        suspend fun create(
            protocol: LlmProtocol,
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
        val protocol = LlmProtocol.fromWire(llmConfig.protocol)
        val runtimeMcpServers = gateway.listMcpServers()
        val pyTools = gateway.listPyTools()
        val builtinSettings = gateway.listBuiltinToolSettings()
        val enabledSkills = gateway.listEnabledSkills()
        val resolvedTools = toolManager.resolve(
            pyTools = pyTools,
            mcpServers = runtimeMcpServers,
            builtinSettings = builtinSettings,
        )
        Logger.i(
            LOG_TAG,
            "tools resolved builtin=${resolvedTools.builtinTools.size} " +
                "py=${resolvedTools.pyTools.size} " +
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
        // 会话实例按协议重建；协议切换 = close + 新实例，但树经 restore 延续
        // （P1 #3：export 当前树给新协议实例，会话 id + 历史跨 Provider 保留）
        val previousSession = runtimeState?.okia
        val activeSession = obtainSession(protocol, configWithoutRuntimePrompt)
        activeSession.update {
            endpoint = configWithoutRuntimePrompt.endpoint
            apiKey = configWithoutRuntimePrompt.apiKey
            model = configWithoutRuntimePrompt.model
            // T2b：MCP 服务器配置进 OKIA（McpDiscovery 发现后注册进同一 toolRegistry）
            mcpServers = toOkiaMcpServers(resolvedTools.mcpServers)
        }
        // T2a：本地工具注册（enabled 集合全量重建；inline 回合内工具由
        // registerPyToolNow 注册，随下次 refresh 由持久化版本接管）
        syncLocalTools(resolvedTools)
        // T2b：MCP 发现（方案 B，D-T2B-3）：签名变化才起后台刷新，不 await
        // （不阻塞回合）；初始化时签名 null → 首次天然触发（启动 eager）
        val mcpSignature = mcpServersSignature(resolvedTools.mcpServers)
        // 新实例（新对话/协议切换）预冷强制刷一次：签名去重会让新实例错过
        // 首轮刷新，发消息时工具未就绪（#switch-refresh）；老实例仍按签名去重
        mcpRefreshScheduler.schedule(
            activeSession,
            mcpSignature,
            force = activeSession !== previousSession,
        )
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
                sessionProtocol = protocol,
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
            protocol = current.sessionProtocol,
            config = current.snapshot.config,
            restore = restore,
            forceNew = true,
        )
        runtimeState = current.copy(okia = newSession)
        // 会话切换预热（#switch-refresh）：restore 建的新实例不带 mcpServers
        // 配置（只有 refresh 会写，discovery 读到空服务器），先补配置再强制
        // 刷一次，抢出用户打字时间窗口，提高首条消息的 MCP 工具就绪率
        val switchMcpServers = current.snapshot.tools.mcpServers
        newSession.update { mcpServers = toOkiaMcpServers(switchMcpServers) }
        mcpRefreshScheduler.schedule(
            newSession,
            mcpServersSignature(switchMcpServers),
            force = true,
        )
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
                    // 用户可见错误文案在 IPC 前用资源本地化。CONFIG_REQUIRED 的异常
                    // message 是内部码（ValidationTest 以 const 断言它），不直接展示
                    val message = if (throwable.toUserErrorCode() == LlmErrorCode.ConfigRequired) {
                        context.getString(R.string.error_config_required)
                    } else {
                        throwable.toUserErrorMessage(defaultErrorMessage)
                    }
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
                    "py=${state.snapshot.tools.pyTools.size} " +
                    "mcp=${state.snapshot.tools.mcpServers.size}"
            )

            val startedAtMs = System.currentTimeMillis()
            var streamErrorReported = false
            var firstFrameLogged = false
            val sink: SendChannel<LlmStreamEvent> = this
            try {
                Logger.i(LOG_TAG, "round started queryLength=${query.length} isUnlocked=${LockState.isUnlocked()}")
                // 异步任务完成通知注入（PRD okia §5.10）：host 侧拼进 send 文本，
                // 不进 hook、不进会话树（通知进树即污染历史）；MCP 发现失败
                // 说明同样前置（Failed 服务器工具不可用，模型需知）
                val notifications = TerminalSessionPool.drainPendingNotifications()
                val mcpNotice = mcpFailureNotice()
                val prefixes = buildList {
                    mcpNotice?.let { add(it) }
                    addAll(notifications)
                }
                val effectiveQuery = if (prefixes.isNotEmpty()) {
                    prefixes.joinToString("\n\n") + "\n\n" + query
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
        sessionProtocol = null
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
        protocol: LlmProtocol?,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot? = null,
        forceNew: Boolean = false,
    ): Okia {
        if (!forceNew && restore == null) {
            okia?.takeIf { sessionProtocol == protocol }?.let { return it }
        }
        // 协议切换（P1 #3）：关旧实例前导出当前树，restore 给新协议实例，
        // 会话 id + 历史跨 Provider 延续（okia §5.7：协议 id 不进会话数据）
        val carried = restore ?: okia?.takeIf { sessionProtocol != protocol }?.export()
        okia?.close()
        return openSession(protocol ?: LlmProtocol.Default, config, carried).also {
            okia = it
            sessionProtocol = protocol
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
        protocol: LlmProtocol,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot?,
    ): Okia = okiaFactory.create(protocol, restore, config)

    /** 端点留空时的兑底：OpenAI Responses / Anthropic / DeepSeek 协议自带官方端点。 */
    private fun protocolDefaultEndpointFallback(protocol: LlmProtocol): String {
        return when (protocol) {
            LlmProtocol.DeepSeek -> "https://api.deepseek.com/chat/completions"
            LlmProtocol.OpenAiChatCompletions -> "https://api.openai.com/v1/chat/completions"
            LlmProtocol.OpenAiResponses -> "https://api.openai.com/v1/responses"
            LlmProtocol.AnthropicMessages -> "https://api.anthropic.com/v1/messages"
        }
    }

    private suspend fun openOkiaWithDefaultProtocol(
        protocol: LlmProtocol,
        restore: SessionSnapshot?,
        config: ResolvedLlmConfig,
    ): Okia {
        val endpoint = config.endpoint.ifBlank { protocolDefaultEndpointFallback(protocol) }
        val wireProtocol = when (protocol) {
            LlmProtocol.DeepSeek -> OpenAIChatCompletionProtocol()
            LlmProtocol.OpenAiChatCompletions ->
                OpenAIChatCompletionProtocol(Json, OpenAIChatCompletionCompat())

            LlmProtocol.OpenAiResponses -> OpenAIResponsesProtocol()
            LlmProtocol.AnthropicMessages -> AnthropicMessagesProtocol()
        }
        return Okia.open(wireProtocol, restore) {
            this.endpoint = endpoint
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
     * pytools write 成功后本轮会以持久化版本重新注册），再注册当前
     * resolved 的 enabled 工具。wireName 为 registry 键（默认
     * ToolWireName.forLocal(name)），同名覆盖无需特判。
     */
    private fun syncLocalTools(tools: ResolvedTools) {
        toolRegistry.snapshot()
            .map { it.descriptor }
            .filter { it.kind is ToolKind.Local }
            .forEach { toolRegistry.remove(it.wireName) }
        (tools.builtinTools + tools.pyTools).forEach { tool ->
            val inputSchemaJson = when (tool) {
                is LocalTool.Builtin -> tool.tool.inputSchemaJson
                is LocalTool.Py -> tool.inputSchemaJson
            }
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
        inlinePyTools.clear()
    }

    /**
     * pytools write 成功且 enabled 的回合内注册（D20）：立即注册进
     * registry，当前回合下一轮模型请求即可见（RealAgentLoop 每段现取
     * snapshot）。下次 refresh 以持久化版本重新注册（同名覆盖）。
     */
    private fun registerPyToolNow(tool: LocalTool.Py) {
        toolRegistry.register(
            ToolDescriptor(
                name = tool.name,
                description = tool.description,
                inputSchemaJson = tool.inputSchemaJson,
                kind = ToolKind.Local,
            ),
            localToolExecutor,
        )
        Logger.i(
            LOG_TAG,
            "py tool registered in-turn name=${tool.name}"
        )
    }

    // ── T2b MCP 发现时序（方案 B，D-T2B-3，对齐 Codex eager + 标脏刷新） ────

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

    /** 失败注入文案（internal 供单测）；格式对齐终端完成通知的元信息风格。 */
    internal fun buildMcpFailureNotice(
        failed: List<McpServerDiscoverySnapshot>,
    ): String = buildString {
        appendLine("[IMPORTANT: MCP discovery failed for the following servers; their tools are unavailable in this turn:")
        failed.forEach { server ->
            val reason = server.errorMessage?.lineSequence()?.firstOrNull()?.take(120) ?: "unknown error"
            appendLine("- ${server.serverName}: $reason")
        }
        append(
            "Do not attempt to call their tools. If the task depends on them, " +
                "tell the user the MCP service is currently unavailable.]",
        )
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

    /**
     * MCP 发现失败注入（#switch-refresh 配套）：send 前读发现快照，
     * 仅 Failed 服务器注入说明（Discovering 是过程态、UsingStaleCache 旧
     * 缓存工具仍可用，均不注）；按「失败集合 + 错误摘要」签名去重，同一
     * 失败片段只注一次，恢复即重置。无 Failed 时顺带打一条状态摘要日志。
     */
    private suspend fun mcpFailureNotice(): String? {
        val session = runtimeState?.okia ?: return null
        val servers = session.getMcpDiscoverySnapshot().servers.values
        Logger.i(
            LOG_TAG,
            "mcp discovery " + servers.sortedBy { it.serverName }
                .joinToString(" ") { "${it.serverName}=${it.state}" },
        )
        val failed = servers.filter { it.state == McpDiscoveryState.Failed }.sortedBy { it.serverName }
        if (failed.isEmpty()) {
            mcpFailureSignature = null
            return null
        }
        val signature = failed.joinToString("|") { "${it.serverName}:${it.errorMessage.orEmpty()}" }
        if (signature == mcpFailureSignature) return null
        mcpFailureSignature = signature
        return buildMcpFailureNotice(failed)
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
        val sessionProtocol: LlmProtocol?,
    )

    private class LlmConfigRequiredException : IllegalStateException(CONFIG_REQUIRED_MESSAGE)
}
