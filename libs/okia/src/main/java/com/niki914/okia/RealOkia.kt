package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.RealConversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.CallbackException
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

    // 单个活跃回合的不可变句柄（job + 起点 entryId + 取消原因）。@Volatile 单字段
    // 整体替换：锁外读者拿到完整一致的快照，不分散为多个 volatile 字段——消除
    // 跨字段一致性边界（CR3 #2 收敛）。null = 无活跃回合。
    @Volatile
    private var activeTurn: ActiveTurn? = null

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
        // 回合状态原子预留（T2 竞态整改）：check + 追加 User + 启动 loop +
        // activeTurn 赋值全部在同一临界区内完成——并发 send / rewind / update /
        // export / close / refreshMcpTools 无法在「check 通过」与「activeTurn
        // 就位」之间插入。先提交 User 再启动 loop（不变量 §5.8：history 永远
        // 包含当前输入）。
        val turnJob: Deferred<TurnResult>
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "another turn is already active" }
            val turnStartEntry = tree.append(Message.User(listOf(ContentBlock.Text(text))))
            publish()

            val request = buildLoopRequest(text, options)
            val job = turnScope.async {
                dependencies.agentLoop.run(request) { event -> handleEvent(event, onEvent) }
            }
            activeTurn = ActiveTurn(job = job, startEntryId = turnStartEntry.id)
            turnJob = job
        }

        return try {
            turnJob.await()
        } catch (e: CancellationException) {
            val cause = activeTurn?.stopCause
            if (cause == null) {
                // 外部取消：与 stop 表现一致（G1 裁决）——先 kill 工具资源
                // （beforeStop）再停掉回合 job，然后传播取消。kill 与 join
                // 都在 NonCancellable 中执行：当前协程已取消，但 kill 步骤与
                // 回合退出等待都不能中断。cancelAndJoin 确保旧回合清理
                // （commitPartial / hook / 事件）真正完成，guard 才由 finally
                // 释放——否则新回合与旧回合清理会同时写 tree/live（CR3 #1）。
                withContext(NonCancellable) {
                    killDispatchedTools(activeTurn?.startEntryId)
                    turnJob.cancelAndJoin()
                }
                throw e
            }
            val message = lastAssistantMessage() ?: AssistantMessage(emptyList())
            handleEvent(TurnEvent.TurnAborted(message, cause), onEvent)
            TurnResult.Aborted(cause)
        } finally {
            // 持有回合状态到终态事件处理完成（Aborted 事件由本方法派发，循环内
            // 终态 Completed/Failed 在 await 返回前已发完）：清除前新回合不得
            // 开始——否则其 TurnAborted 的 live=null 会冲掉新回合的 live。
            // 整个句柄置 null：stopCause / startEntryId 随旧句柄一起清除，不跨
            // 回合残留（CR3 #2）。
            activeTurn = null
        }
    }

    override suspend fun stop() {
        // 取得活跃句柄 + 确认 job 仍活跃 + 写 stopCause，三者同一临界区原子完成。
        // job 已完成但 send finally 尚未清句柄时（回合自然完成的短窗口）是
        // no-op：不写陈旧 stopCause（CR3 #2，避免残留使下回合 stop 失效/取消误判）。
        val turn = mutex.withLock {
            val t = activeTurn
            if (t == null || !t.job.isActive || t.stopCause != null) null
            else {
                activeTurn = t.copy(stopCause = StopCause.UserStop)
                t
            }
        } ?: return
        // G2：每回合至多一次 kill——stopCause 已在临界区去重，只有首个读到
        // stopCause=null 的调用会写 UserStop 并走到取消。
        // kill-then-stop（§5.11）：先 kill 工具资源（beforeStop，异常捕获不中止）
        // 再取消回合 job（阻塞工具不吃协程取消，直接 cancel 会永久挂住）。
        killDispatchedTools(turn.startEntryId)
        turn.job.cancelAndJoin()
    }

    override suspend fun rewind(entryId: String) {
        // 检查 + 操作同一临界区（评审发现 5）：并发 send 不能在 check 通过后、
        // tree.rewind 前启动（否则活跃回合中回退历史，loop 与树 leaf 不一致）
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot rewind during active turn" }
            tree.rewind(entryId)
            publish()
        }
    }

    override suspend fun export(): SessionSnapshot {
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "cannot export during active turn" }
            return SessionSnapshot(id = tree.id, leafId = tree.leafId, version = 1, entries = tree.entries)
        }
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
        // 不与 send 争门面锁（issue #125）：McpDiscovery 自带串行化（内部 mutex +
        // @Volatile snapshot），发现状态与会话树/回合生命周期独立；注册表变更经
        // effectiveRegistry 即时生效，agent loop 每请求现取 registry.snapshot()，
        // 回合内刷新语义安全。
        check(!closed) { "Okia is closed" }
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
        try {
            onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 业务回调失败：包装后传播（loop 分类为不可重试的 callback failure），
            // 不伪装成网络错误、不触发请求重发（问题 1）
            throw CallbackException(e)
        }
        eventsFlow.emit(event)
    }

    private fun publish() {
        conversationFlow.value = tree.toSnapshot(live)
    }

    // kill 步骤（§5.11）：推导本回合已派发的工具调用（起点之后已提交 Assistant
    // 中的 ToolCall 块），按注册顺序跑 beforeStop 链。hook 异常被捕获，不中止
    // 停止流程；CancellationException 也捕获（kill 步骤必须跑完，§5.11）。
    // startEntryId 取自捕获的回合句柄，不重读 activeTurn（调用方已在锁内确定它）。
    private suspend fun killDispatchedTools(startEntryId: String?) {
        val calls = startEntryId?.let { tree.assistantToolCallsSince(it) } ?: emptyList()
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

// 单个活跃回合的不可变句柄。构造后不修改（stop 用 copy 替换整个句柄），
// @Volatile 单字段整体替换 → 锁外读者拿到一致快照（CR3 #2 收敛）。
private data class ActiveTurn(
    val job: Deferred<TurnResult>,
    val startEntryId: String?,
    val stopCause: StopCause? = null
)
