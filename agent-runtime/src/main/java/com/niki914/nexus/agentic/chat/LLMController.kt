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
import com.niki914.kai.ChatTurn
import com.niki914.kai.ToolCallSpec
import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.OpenAIChatCompletionCompat
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.util.UUID
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

/**
 * Nexus 的 LLM 回合执行入口。OKIA 接入 T1 重写：
 * - 运行时从 Kai 切到 Okia（一次对话一个实例：换会话/重建 = close + open(restore)）
 * - 终态以 send 返回值（TurnResult）承载，事件流只承担中间过程
 * - 工具注册/执行/MCP 发现留给 T2：T1 不注册工具（模型可能调用未注册工具 →
 *   LLMErrorCode.UnknownTool 失败，已知退化）；kill-then-stop 已下沉到
 *   Hooks.beforeStop（OKIA stop() 先杀资源再取消 job）
 * - getHistory/replaceHistory/resetConversation 为 ChatTurn 桥接（O1-A 过渡，
 *   T3 由 export()/open(restore) 会话生命周期替代）
 */
object LLMController {
    private const val LOG_TAG = "niki914_nexus_LLMController"
    internal const val CONFIG_REQUIRED_MESSAGE = "请先填写配置" // <--- TODO res
    private const val LLM_IDLE_TIMEOUT_SECONDS = 50L

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

    // 测试注入点：T1 单测经 Okia.open(dependencies) 装配 fake loop/mapper。 <--- TODO Workaround???
    internal var okiaFactory: OkiaFactory = OkiaFactory { apiType, restore, config ->
        openOkiaWithDefaultProtocol(apiType, restore, config)
    }

    internal fun resetForTest() {
        kotlinx.coroutines.runBlocking { okia?.close() }
        okia = null
        sessionApiType = null
        runtimeState = null
        toolRegistry.snapshot().forEach { toolRegistry.remove(it.descriptor.wireName) }
        inlineCustomTools.clear()
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
        }
        // T2a：本地工具注册（enabled 集合全量重建；inline 回合内工具由
        // registerCustomToolNow 注册，随下次 refresh 由持久化版本接管）
        syncLocalTools(resolvedTools)
        // T1 工具退化：工具描述仍进入提示词（技能/记忆段依赖它），但注册表为空
        // （不向请求注入 tool 定义）——模型若调用工具 → UnknownTool 失败（T2 接入）。
        // MCP 发现/指纹决策已删（T2 下沉到 OKIA McpDiscovery）。
        val prompt = promptComposer.compose(
            PromptComposerInput(
                additionalInstructions = llmConfig.prompt,
                memoryItems = buildMemoryItems(llmConfig),
                tools = resolvedTools,
                mcpDiscoverySnapshot = null,
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
     * ChatTurn 桥接（O1-A 过渡，T3 移除）：从当前会话树投影为 Kai 时代的历史格式。
     * OKIA 树是唯一事实；本方法只服务主 App 会话列表/持久化的既有消费端。
     */
    suspend fun getHistory(): List<ChatTurn> {
        val startedAtMs = System.currentTimeMillis()
        val history = okia
            ?.conversation
            ?.value
            ?.history
            ?.mapNotNull { it.message.toChatTurn() }
            .orEmpty()
        Logger.d(
            LOG_TAG,
            "get history turnCount=${history.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return history
    }

    /**
     * ChatTurn 桥接（O1-A 过渡，T3 移除）：丢弃当前实例的会话树，
     * 以给定历史重建新实例（OKIA 无原地换历史 API，换树 = 换实例）。
     */
    suspend fun replaceHistory(history: List<ChatTurn>) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "replace history turnCount=${history.size} started")
        refresh()
        val state = runtimeState ?: return
        val restore = buildSnapshotFromChatTurns(history)
        val newSession = obtainSession(
            apiType = state.sessionApiType,
            config = state.snapshot.config,
            restore = restore,
            forceNew = true,
        )
        runtimeState = state.copy(okia = newSession)
        Logger.i(
            LOG_TAG,
            "replace history done turnCount=${history.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

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
        // 先杀 python worker / 关 terminal 会话：Binder 调用与 exec 立即结束，
        // 工具协程快速死亡，新会话不继承上一个回合的工具状态
        PyRuntime.kill()
        TerminalSessionPool.closeAll()
        val state = runtimeState ?: return
        obtainSession(
            apiType = state.sessionApiType,
            config = state.snapshot.config,
            restore = null,
            forceNew = true,
        )
        Logger.i(LOG_TAG, "reset conversation done")
    }

    suspend fun stopCurrentRound(keepCurrentTurn: Boolean = false) {
        Logger.i(LOG_TAG, "stop round requested keepCurrentTurn=$keepCurrentTurn")
        // OKIA stop() 内建 kill-then-stop：beforeStop hook（杀 py/tty）先于
        // 取消 job 执行，阻塞工具不再吃得协程取消（§5.11）。
        // keepCurrentTurn 保留兼容调用方：OKIA 停止不动会话树，下一轮自然承接
        // 历史（语义恒为 true）；参数为历史遗留，T4 清理。
        okia?.stop()
        Logger.i(LOG_TAG, "stop round done keepCurrentTurn=$keepCurrentTurn")
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

    // ── ChatTurn ↔ OKIA 树 桥接（O1-A 过渡，T3 移除） ────────────────────────

    private fun buildSnapshotFromChatTurns(history: List<ChatTurn>): SessionSnapshot {
        var parentId: String? = null
        val entries = buildList {
            history.forEach { turn ->
                val message = turn.toOkiaMessage() ?: return@forEach
                val entry = ConversationEntry(
                    id = UUID.randomUUID().toString(),
                    parentId = parentId,
                    timestamp = System.currentTimeMillis(),
                    message = message,
                )
                add(entry)
                parentId = entry.id
            }
        }
        return SessionSnapshot(
            id = UUID.randomUUID().toString(),
            leafId = entries.lastOrNull()?.id,
            version = 1,
            entries = entries,
        )
    }

    private fun ChatTurn.toOkiaMessage(): Message? = when (this) {
        is ChatTurn.User -> Message.User(listOf(ContentBlock.Text(content)))

        is ChatTurn.Assistant -> Message.Assistant(
            AssistantMessage(
                content = buildList {
                    reasoningContent?.takeIf { it.isNotBlank() }
                        ?.let { add(ContentBlock.Thinking(it, signature = reasoningSignature)) }
                    add(ContentBlock.Text(content))
                    toolCalls.forEach { call ->
                        add(ContentBlock.ToolCall(call.callId, call.toolName, call.argumentsJson))
                    }
                },
                reasoningSignature = reasoningSignature,
            )
        )

        is ChatTurn.ToolResult ->
            Message.ToolResult(callId, toolName, ToolCallOutcome.Success(content = resultJson))

        is ChatTurn.System -> null
    }

    private fun Message.toChatTurn(): ChatTurn? = when (this) {
        is Message.User -> ChatTurn.User(content = content.textBlocks().joinToString("\n"))

        is Message.Assistant -> ChatTurn.Assistant(
            content = message.content.textBlocks().joinToString("\n"),
            toolCalls = message.content
                .filterIsInstance<ContentBlock.ToolCall>()
                .map { ToolCallSpec(it.id, it.name, it.argumentsJson) },
            reasoningContent = message.content
                .filterIsInstance<ContentBlock.Thinking>()
                .joinToString("\n") { it.text }
                .takeIf { it.isNotBlank() },
            reasoningSignature = message.reasoningSignature,
        )

        is Message.ToolResult ->
            ChatTurn.ToolResult(callId, toolName, outcome.contentText())
    }

    private fun List<ContentBlock>.textBlocks(): List<String> =
        filterIsInstance<ContentBlock.Text>().map { it.text }

    private fun com.niki914.okia.message.ToolCallOutcome.contentText(): String = when (this) {
        is com.niki914.okia.message.ToolCallOutcome.Success -> content
        is com.niki914.okia.message.ToolCallOutcome.Failure -> content.orEmpty()
        is com.niki914.okia.message.ToolCallOutcome.Intercepted -> content.orEmpty()
        is com.niki914.okia.message.ToolCallOutcome.Interrupted -> content.orEmpty()
        is com.niki914.okia.message.ToolCallOutcome.Unknown -> content.orEmpty()
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