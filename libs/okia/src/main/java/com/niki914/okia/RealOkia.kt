package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.RealConversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.LoopOptions
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpDiscovery
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.OkHttpEngine
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Okia 门面实现：一次对话一个实例，至多一个活跃回合。
 * 状态投影：conversation StateFlow 每次发射不可变快照（history + live）；
 * live 只在 turn 协程内写（事件处理与 onCommit 同线程顺序执行），
 * 流式期间只更新 live，消息完整（onCommit）才进 history——
 * 不变量：live 非空 ⇒ history 不含该消息（2026-08-16 对齐）。
 * 并发契约（§5.2 / §8.7 #5）：活跃回合存在时 send / rewind / update /
 * export / close 抛异常；stop 是唯一例外。Aborted 终态由本协调器在取消
 * job 后按 stopCause 产生（§8.8 #2），stop 置 UserStop，外部取消传播。
 * 资源所有权（§5.13）：注入资源宿主所有不释放；默认资源（EmptyToolRegistry）
 * 实例所有；close 只取消 turnScope 并标记 closed。
 * Design source: okia PRD §5.1 / §5.2 / §5.4 / §8.8；OkHttp Real* 命名惯例。
 */
@OptIn(ExperimentalUuidApi::class)
internal class RealOkia(
    private val dependencies: OkiaDependencies,
    restore: SessionSnapshot?,
    // 初始配置；与属性 config 不同名（遮蔽坑 §8.10 #4：同名时 by lazy 内
    // 嵌套 lambda 会捕获构造参数值而非属性字段）
    initialConfig: OkiaConfig,
    // 回合执行 scope；测试注入 TestDispatcher 获得可控时序（默认真实线程池）
    private val turnScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : Okia {

    private val tree = RealConversation(
        id = restore?.id ?: Uuid.random().toString(),
        initialEntries = restore?.entries ?: emptyList(),
        initialLeafId = restore?.leafId
    )

    private val conversationFlow = MutableStateFlow(tree.toSnapshot())
    override val conversation: StateFlow<Conversation> = conversationFlow

    private val eventsFlow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TurnEvent> = eventsFlow

    @Volatile
    private var config: OkiaConfig = initialConfig

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var activeTurn: Deferred<TurnResult>? = null

    // stop() 记录的取消原因；send 捕获取消后读取并清零
    @Volatile
    private var stopCause: StopCause? = null

    // 当前回合起点（send 提交的 User 消息 entryId）；stop / 外部取消时经会话树
    // 推导本回合已派发的工具调用（beforeStop 参数，§8.15 #7，kill-then-stop）
    @Volatile
    private var turnStartEntryId: String? = null

    // 正在流式、尚未成条的助手消息；只在 turn 协程内写
    private var live: AssistantMessage? = null

    // 默认 HttpEngine：config 未注入时自建（实例所有；OkHttp 无显式释放语义，
    // close 不释放——连接池到期自保洁，§8.17）
    private val defaultEngine by lazy { OkHttpEngine() }

    // 默认工具注册表：config 未注入 toolRegistry 时门面持有（实例所有，T9b）。
    // MCP 发现结果注册进它（refreshMcpTools）；EmptyToolRegistry 已删除（T9b）。
    private val defaultRegistry = DefaultToolRegistry()

    // 当前生效注册表：config 注入的或默认实例（单一注册表来源，§8.7 #7）
    private fun effectiveRegistry(cfg: OkiaConfig): ToolRegistry = cfg.toolRegistry ?: defaultRegistry

    // MCP 发现管理：servers / registry 闭包读最新 config（update 热更新可见）
    private val mcpDiscovery by lazy {
        McpDiscovery(
            client = dependencies.mcpClient,
            servers = { config.mcpServers },
            registry = { effectiveRegistry(config) }
        )
    }

    private val mutex = Mutex()

    override suspend fun send(
        text: String,
        options: TurnOptions?,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "another turn is already active" }
        }

        // 不变量（§5.8）：history 永远包含当前输入——先提交 User 再启动 loop
        val turnStartEntry = tree.append(Message.User(listOf(ContentBlock.Text(text))))
        turnStartEntryId = turnStartEntry.id
        publish()

        val request = buildLoopRequest(text, options)
        val turnJob = turnScope.async {
            try {
                dependencies.agentLoop.run(request) { event -> handleEvent(event, onEvent) }
            } finally {
                activeTurn = null
                turnStartEntryId = null
            }
        }
        activeTurn = turnJob

        return try {
            turnJob.await()
        } catch (e: CancellationException) {
            val cause = stopCause
            stopCause = null
            if (cause == null) {
                // 外部取消：与 stop 表现一致（G1 裁决）——先 kill 工具资源
                // （beforeStop）再停掉回合 job，然后传播取消。kill 在
                // NonCancellable 中执行：当前协程已取消，但 kill 步骤不能中断。
                withContext(NonCancellable) {
                    killDispatchedTools()
                    turnJob.cancel()
                }
                throw e
            }
            val message = lastAssistantMessage() ?: AssistantMessage(emptyList())
            handleEvent(TurnEvent.TurnAborted(message, cause), onEvent)
            TurnResult.Aborted(cause)
        }
    }

    override suspend fun stop() {
        val job = activeTurn ?: return
        // G2：每回合至多一次 kill——并发/重入 stop 只让第一个通过。
        // mutex 保证检查+写入原子（stopCause @Volatile 读不够：两个并发 stop
        // 可能都读到 null）。
        mutex.withLock {
            if (stopCause != null) return
            stopCause = StopCause.UserStop
        }
        // kill-then-stop（§5.11）：先 kill 工具资源（beforeStop，异常捕获不中止）
        // 再取消回合 job（阻塞工具不吃协程取消，直接 cancel 会永久挂住）。
        killDispatchedTools()
        job.cancelAndJoin()
    }

    override suspend fun rewind(entryId: String) {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot rewind during active turn" }
        }
        tree.rewind(entryId)
        publish()
    }

    override suspend fun export(): SessionSnapshot {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot export during active turn" }
        }
        return SessionSnapshot(id = tree.id, leafId = tree.leafId, version = 1, entries = tree.entries)
    }

    override suspend fun update(block: OkiaConfig.Builder.() -> Unit) {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot update during active turn" }
            config = OkiaConfig.Builder().copyFrom(config).apply(block).build()
        }
    }

    override suspend fun config(): OkiaConfig = config

    override suspend fun refreshMcpTools(): McpRefreshResult {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot refreshMcpTools during active turn" }
        }
        return mcpDiscovery.refresh()
    }

    // 只读快照；活跃回合允许（并发契约 §8.7 #5 的列表不含本方法，读不与
    // 提交竞争——发现状态与会话树独立）
    override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot {
        check(!closed) { "Okia is closed" }
        return mcpDiscovery.current()
    }

    override suspend fun close() {
        mutex.withLock {
            check(!closed) { "Okia is already closed" }
            check(activeTurn == null) { "cannot close during active turn" }
            closed = true
        }
        turnScope.cancel()
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun buildLoopRequest(text: String, options: TurnOptions?): LoopRequest {
        val cfg = config
        val engine = cfg.httpEngine ?: defaultEngine
        val snapshot = RequestSnapshot(
            endpoint = cfg.endpoint,
            apiKey = cfg.apiKey,
            model = options?.model ?: cfg.model,
            systemPrompt = options?.systemPrompt,
            temperature = options?.temperature ?: cfg.temperature,
            maxTokens = options?.maxTokens ?: cfg.maxTokens,
            headers = cfg.headers,
            timeouts = HttpTimeouts(
                connectMs = cfg.connectTimeoutSeconds * 1000,
                readMs = cfg.readTimeoutSeconds * 1000,
                writeMs = cfg.writeTimeoutSeconds * 1000
            ),
            tools = effectiveRegistry(cfg).snapshot().map { it.descriptor }
            // 工具描述快照（T9b G5 整改）：send 时快照仅为初始值；每段
            // buildRequest 前 RealAgentLoop 用 registry 现取覆盖（§8.18），
            // 请求体表达「每段发送时的工具集」。
        )
        return LoopRequest(
            snapshot = snapshot,
            history = tree.history,
            input = text,
            options = options?.loopOptions ?: LoopOptions(),
            idleTimeoutSeconds = cfg.idleTimeoutSeconds,
            toolRegistry = effectiveRegistry(cfg),
            protocolMapper = dependencies.protocolMapper,
            hooks = cfg.hooks,
            httpEngine = engine,
            retryPolicy = cfg.retryPolicy,
            onCommit = { messages ->
                tree.appendAll(messages)
                live = null
                publish()
            }
        )
    }

    // 事件 → 状态投影（live）+ 转发调用方 + 发射事件流。三者同步执行，
    // StateFlow conflate 下消费者看到的快照与事件序一致。
    private suspend fun handleEvent(event: TurnEvent, onEvent: suspend (TurnEvent) -> Unit) {
        when (event) {
            is TurnEvent.TextStarted -> live = event.partial
            is TurnEvent.TextDelta -> live = event.partial
            is TurnEvent.TextEnded -> live = event.partial
            is TurnEvent.ThinkingStarted -> live = event.partial
            is TurnEvent.ThinkingDelta -> live = event.partial
            is TurnEvent.ThinkingEnded -> live = event.partial
            is TurnEvent.ToolCallStarted -> live = event.partial
            is TurnEvent.ToolCallDelta -> live = event.partial
            is TurnEvent.ToolCallReady -> live = event.partial
            is TurnEvent.ToolRunning -> live = event.partial
            is TurnEvent.ToolSucceeded -> live = event.partial
            is TurnEvent.ToolFailed -> live = event.partial
            is TurnEvent.TurnStarted -> Unit
            is TurnEvent.RetryScheduled -> Unit
            is TurnEvent.TurnCompleted,
            is TurnEvent.TurnFailed,
            is TurnEvent.TurnAborted,
            is TurnEvent.TurnIdleTimeout -> live = null
        }
        publish()
        onEvent(event)
        eventsFlow.emit(event)
    }

    private fun publish() {
        conversationFlow.value = tree.toSnapshot(live)
    }

    // kill 步骤（§5.11）：推导本回合已派发的工具调用（起点之后已提交 Assistant
    // 中的 ToolCall 块），按注册顺序跑 beforeStop 链。hook 异常被捕获，不中止
    // 停止流程；CancellationException 也捕获（kill 步骤必须跑完，§5.11）。
    private suspend fun killDispatchedTools() {
        val calls = turnStartEntryId?.let { tree.assistantToolCallsSince(it) } ?: emptyList()
        for (hook in config.hooks) {
            try {
                hook.beforeStop(calls)
            } catch (e: Exception) {
                // 捕获：kill 步骤不因 hook 失败而中断
            }
        }
    }

    private fun lastAssistantMessage(): AssistantMessage? =
        tree.history.asReversed().filterIsInstance<Message.Assistant>().firstOrNull()?.message
}
