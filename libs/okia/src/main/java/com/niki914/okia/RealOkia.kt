package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.RealConversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.LoopOptions
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.EmptyToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
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
    config: OkiaConfig,
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
    private var config: OkiaConfig = config

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var activeTurn: Deferred<TurnResult>? = null

    // stop() 记录的取消原因；send 捕获取消后读取并清零
    @Volatile
    private var stopCause: StopCause? = null

    // 正在流式、尚未成条的助手消息；只在 turn 协程内写
    private var live: AssistantMessage? = null

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
        tree.append(Message.User(listOf(ContentBlock.Text(text))))
        publish()

        val request = buildLoopRequest(text, options)
        val turnJob = turnScope.async {
            try {
                dependencies.agentLoop.run(request) { event -> handleEvent(event, onEvent) }
            } finally {
                activeTurn = null
            }
        }
        activeTurn = turnJob

        return try {
            turnJob.await()
        } catch (e: CancellationException) {
            val cause = stopCause
            stopCause = null
            if (cause == null) {
                // 外部取消：传播（协程取消语义优先），同时停掉回合 job
                turnJob.cancel()
                throw e
            }
            val message = lastAssistantMessage() ?: AssistantMessage(emptyList())
            handleEvent(TurnEvent.TurnAborted(message, cause), onEvent)
            TurnResult.Aborted(cause)
        }
    }

    override suspend fun stop() {
        val job = activeTurn ?: return
        stopCause = StopCause.UserStop
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

    override suspend fun refreshMcpTools(): McpRefreshResult = TODO("MCP refresh lands in T8")

    override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot = TODO("MCP lands in T8")

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
        val engine = cfg.httpEngine
            ?: throw IllegalStateException("default HttpEngine is not implemented yet (T8); inject one via OkiaConfig.httpEngine")
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
            tools = (cfg.toolRegistry ?: EmptyToolRegistry()).snapshot().map { it.descriptor }
        )
        return LoopRequest(
            snapshot = snapshot,
            history = tree.history,
            input = text,
            options = options?.loopOptions ?: LoopOptions(),
            idleTimeoutSeconds = cfg.idleTimeoutSeconds,
            toolRegistry = cfg.toolRegistry ?: EmptyToolRegistry(),
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

    private fun lastAssistantMessage(): AssistantMessage? =
        tree.history.asReversed().filterIsInstance<Message.Assistant>().firstOrNull()?.message
}
