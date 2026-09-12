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
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.concurrent.Volatile
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
 * 停止定界（AC9）：每个 send 拥有一个不可变回合句柄，stop(token) 只命中绑定该
 * token 的回合；选中、kill/join 清理、guard 释放全部限定在该句柄。guard 由拥有者
 * send 在清理与终态回调完成后比较释放（activeTurn === turn），因此 stop 选择后
 * 即使回合自然完成，也不会有后继回合在旧回合清理期间被接纳——kill 的工具后缀
 * 扫描不可能含后继回合的调用。
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
    // close 不释放——连接池到期自保洁，§8.17）。proxy 同步在 buildLoopRequest
    // 完成：每次请求读最新 config 快照，update 热更新代理后下一段请求即生效
    private val defaultEngine by lazy { OkHttpEngine(proxyUrl = config.proxy) }

    // 默认工具注册表：config 未注入 toolRegistry 时门面持有（实例所有，T9b）。
    // MCP 发现结果注册进它（refreshMcpTools）；EmptyToolRegistry 已删除（T9b）。
    private val defaultRegistry = DefaultToolRegistry()

    // 当前生效注册表：config 注入的或默认实例（单一注册表来源，§8.7 #7）
    private fun effectiveRegistry(cfg: OkiaConfig): ToolRegistry =
        cfg.toolRegistry ?: defaultRegistry

    // MCP 发现管理：servers / registry 闭包读最新 config（update 热更新可见）
    private val mcpDiscovery by lazy {
        McpDiscovery(
            client = dependencies.mcpClient,
            servers = { config.mcpServers },
            registry = { effectiveRegistry(config) },
            imageSaver = config.imageSaver
        )
    }

    private val mutex = Mutex()

    override suspend fun send(
        text: String,
        images: List<ContentBlock.Image>,
        options: TurnOptions?,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult {
        // 回合状态原子预留（T2 竞态整改）：check + 追加 User + 启动 loop +
        // activeTurn 赋值全部在同一临界区内完成——并发 send / rewind / update /
        // export / close / refreshMcpTools 无法在「check 通过」与「activeTurn
        // 就位」之间插入。先提交 User 再启动 loop（不变量 §5.8：history 永远
        // 包含当前输入）。
        // 本回合句柄是本方法后续一切清理与 guard 释放的唯一身份：kill、join、
        // 终态分支、guard 比较都用它，绝不重读 activeTurn。
        val turn: ActiveTurn
        mutex.withLock {
            check(!closed) { "Okia is closed" }
            check(activeTurn == null) { "another turn is already active" }
            // text + images 构成用户消息内容块（纯图片时不生成空 Text 块）
            val userBlocks = buildList {
                if (text.isNotBlank()) add(ContentBlock.Text(text))
                addAll(images)
            }
            val turnStartEntry = tree.append(Message.User(userBlocks))
            publish()

            val request = buildLoopRequest(text, options)
            val job = turnScope.async {
                dependencies.agentLoop.run(request) { event -> handleEvent(event, onEvent) }
            }
            turn = ActiveTurn(
                token = options?.turnToken ?: Any(),
                job = job,
                startEntryId = turnStartEntry.id
            )
            activeTurn = turn
        }

        return try {
            turn.job.await()
        } catch (e: CancellationException) {
            // 基线语义：进入 catch 立刻读 stopCause 定分支。外部取消与 stop 竞争时，
            // 若本次 await 抛取消发生在前，则无论清理期间是否有 stop 写入 UserStop，
            // 都走外部分支（传播取消，不写 live 清空）——Host 的 cancel-then-async-stop
            // 不被强制成 UserStop。必须在 ensureCleanup 挂起前捕获。
            val stopCause = turn.stopCause
            // 外部取消与 stop 都会让 await 抛取消：先保证本回合清理（kill→join）
            // 完成再进入终态分支。cleanup 以回合句柄为界，且 guard 未释放期间
            // 后继 send 无法被接纳——kill 的工具后缀扫描不可能含后继回合调用。
            ensureCleanup(turn)
            if (stopCause == null) {
                // 外部取消：与 stop 表现一致（G1 裁决）——先 kill 工具资源
                // （beforeStop）再停掉回合 job，然后传播取消。清理在
                // NonCancellable 中执行：当前协程已取消，但 kill 步骤与回合退出
                // 等待都不能中断。cancelAndJoin 确保旧回合清理（commitPartial /
                // hook / 事件）真正完成，guard 才由 finally 释放——否则新回合与
                // 旧回合清理会同时写 tree/live（CR3 #1）。
                throw e
            }
            val message = lastAssistantMessage() ?: AssistantMessage(emptyList())
            handleEvent(TurnEvent.TurnAborted(message, stopCause), onEvent)
            TurnResult.Aborted(stopCause)
        } finally {
            // 持有回合状态到终态事件处理完成（Aborted 事件由本方法派发，循环内
            // 终态 Completed/Failed 在 await 返回前已发完）：清除前新回合不得
            // 开始——否则其 TurnAborted 的 live=null 会冲掉新回合的 live。
            // 「认领检查 + guard 释放」必须在同一临界区：stop 的「写 stopCause +
            // 认领清理」也在锁内，两者互斥，杜绝「stop 已认领但 send finally 先放行
            // T2、kill 迟到」的窗口（AC9）。已认领则等在途清理完成再释放。
            // 句柄整体置 null：stopCause / startEntryId / 认领位随旧句柄一起清除，
            // 不跨回合残留（CR3 #2）。
            // 整个流程（含两次锁获取与 cleaned 等待）都在 NonCancellable 内：拥有者
            // Job 已取消时，在争用的 mutex 上取锁会抛取消并漏掉 guard 清除——取消
            // 不得越过清理保证（要么在此完成清除，要么由认领者完成清理后此前已清）。
            withContext(NonCancellable) {
                val pending = mutex.withLock {
                    if (turn.cleanupClaimed) true
                    else {
                        if (activeTurn === turn) activeTurn = null
                        false
                    }
                }
                if (pending) {
                    turn.cleaned.await()
                    mutex.withLock { if (activeTurn === turn) activeTurn = null }
                }
            }
        }
    }

    override suspend fun stop() {
        requestStop(expectedToken = null)
    }

    override suspend fun stop(turnToken: Any): Boolean = requestStop(expectedToken = turnToken)

    // 停止选择与认领：取得活跃句柄 + 校验目标 token（值相等）+ 确认 job 仍活跃
    // + 写 stopCause + 认领清理，全部同一临界区原子完成。job 已完成但 send finally
    // 尚未清句柄时（回合自然完成窗口）是 no-op：不写陈旧 stopCause（CR3 #2，避免
    // 残留使下回合 stop 失效/取消误判），也不认领（kill 无从谈起）。
    // G2：每回合至多一次 kill——stopCause 在临界区去重，只有首个调用认领清理。
    // 认领与 stopCause 同锁写入，使得拥有者 send 的「检查+释放」无法越过它。
    private suspend fun requestStop(expectedToken: Any?): Boolean {
        val (turn, claimed) = mutex.withLock {
            val t = activeTurn ?: return false
            // token 值相等即命中（调用方可重建身份）；无参 stop 不校验 token。
            if (expectedToken != null && t.token != expectedToken) return false
            if (!t.job.isActive || t.stopCause != null) return false
            t.stopCause = StopCause.UserStop
            t to t.tryClaimCleanup()
        }
        // kill-then-stop（§5.11）：先 kill 工具资源（beforeStop，异常捕获不中止）
        // 再取消回合 job（阻塞工具不吃协程取消，直接 cancel 会永久挂住）。清理一旦
        // 被本次调用认领就在 NonCancellable 中跑到发信号；被他人认领则等其完成。
        if (claimed) runCleanup(turn) else withContext(NonCancellable) { turn.cleaned.await() }
        return true
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
            return SessionSnapshot(
                id = tree.id,
                leafId = tree.leafId,
                version = 1,
                entries = tree.entries
            )
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
        val engine = cfg.httpEngine ?: defaultEngine.also { it.updateProxy(cfg.proxy) }
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
            tools = effectiveRegistry(cfg).snapshot().map { it.descriptor },
            supportsImages = cfg.supportsImages,
            imageLoader = cfg.imageLoader,
            thinkingLevel = cfg.thinkingLevel
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

    // 认领本回合清理：至多一次 kill/join 链。kill/join 在 NonCancellable 中跑到
    // 发信号（[ActiveTurn.cleaned]）为止——停止调用方或回合协程被取消都不能半途
    // 放弃，否则 guard 会在「kill 未跑到、loop 仍在」时被释放。未认领者只等待，
    // 不重复触发进程级 beforeStop。
    private suspend fun ensureCleanup(turn: ActiveTurn) {
        // 认领必须在锁内：与 send finally 的「检查+释放」互斥（AC9）。
        val claimed = withContext(NonCancellable) {
            mutex.withLock { turn.tryClaimCleanup() }
        }
        if (claimed) runCleanup(turn) else withContext(NonCancellable) { turn.cleaned.await() }
    }

    private suspend fun runCleanup(turn: ActiveTurn) {
        withContext(NonCancellable) {
            try {
                killDispatchedTools(turn.startEntryId)
            } finally {
                // kill 抛错也必须取消并等回合退出：join 未完成就放行 guard 会让
                // 后继回合与旧回合清理并发写 tree/live。
                turn.job.cancelAndJoin()
                turn.cleaned.complete(Unit)
            }
        }
    }

    // kill 步骤（§5.11）：推导本回合已派发的工具调用（起点之后已提交 Assistant
    // 中的 ToolCall 块），按注册顺序跑 beforeStop 链。hook 异常被捕获，不中止
    // 停止流程；CancellationException 也捕获（kill 步骤必须跑完，§5.11）。
    // startEntryId 取自捕获的回合句柄，不重读 activeTurn（调用方已在锁内确定它）；
    // 扫描上界由「本回合清理期间 guard 不放行」保证，不需要额外边界。
    private suspend fun killDispatchedTools(startEntryId: String) {
        val calls = tree.assistantToolCallsSince(startEntryId)
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

// 单个活跃回合的句柄：token / job / 起点不可变，停止状态与清理认领在 mutex
// 临界区内写（stopCause 另加 @Volatile 供取消路径锁外读取）。句柄一次性使用，
// 回合结束后整体置 null，不跨回合残留（CR3 #2）。
private class ActiveTurn(
    val token: Any,
    val job: Deferred<TurnResult>,
    val startEntryId: String
) {
    /** 停止原因；仅在 [RealOkia] 的 mutex 临界区内写，锁外只读。 */
    @Volatile
    var stopCause: StopCause? = null

    /** 清理认领位；仅在 mutex 临界区内读写（认领者必须调用 runCleanup）。 */
    var cleanupClaimed: Boolean = false

    /** 清理完成信号：guard 释放与 stop 返回都等它。 */
    val cleaned: CompletableDeferred<Unit> = CompletableDeferred()

    fun tryClaimCleanup(): Boolean = if (cleanupClaimed) false else {
        cleanupClaimed = true
        true
    }
}
