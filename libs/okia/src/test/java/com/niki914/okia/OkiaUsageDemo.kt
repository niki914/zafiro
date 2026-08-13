package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.ToolCallHolder
import com.niki914.okia.hooks.ToolResultHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 多轮对话 + 伪 Compose UI 驱动演示。
 *
 * 骨架阶段库侧全部 TODO() 占位，本文件演示 host 侧使用形态：
 * 1. UI 只观察 `conversation: StateFlow<Conversation>`，每次发射整帧重渲染；
 * 2. 打字机 = 快照的 `live` 字段（流式 partial 逐 delta 更新），与 onCommit 无关——
 *    onCommit 是库内部"消息成条"通道，UI 永远见不到它；
 * 3. 工具状态 = Running（已提交 ToolCall 无对应 ToolResult）/ Outcome（配到终态），
 *    由 history 推导，UI 零额外状态；
 * 4. turn 边界由下游按 Message.User 自行封装（库不提供 turn 分组）。
 *
 * Compose 对照：本文件的 collect + 渲染 = `conversation.collectAsState()` +
 * recomposition；history 遍历 = LazyColumn item；live = 末尾打字机 Text。
 * Design source: okia PRD §5.4（StateFlow 投影 / live / 消息级更新）。
 */
fun main() = runBlocking {

    // ── 装配 ──────────────────────────────────────────────────────────────
    val registry: ToolRegistry = TODO()
    registry.register(CalculatorTool.descriptor, CalculatorTool())
    registry.register(SearchTool.descriptor, SearchTool())

    val okia: Okia = Okia.open {
        endpoint = "https://api.deepseek.com/v1"
        model = "deepseek-chat"
        hooks = listOf(AuditHook())
        toolRegistry = registry
    }

    // ── UI 层（伪 Compose）：订阅状态流，每次发射重渲染一帧 ──────────────
    // Compose 中等价写法：val snap by okia.conversation.collectAsState()
    val uiJob: Job = launch {
        okia.conversation.collect { snapshot -> renderFrame(snapshot) }
    }

    // 宿主 IPC 通道：一次性事件流（RenderFrame 流式回调形态，见 kai 实证）
    val eventJob: Job = launch {
        okia.events.collect { event -> onTurnEvent(event) }
    }

    // ── 多轮对话 ──────────────────────────────────────────────────────────
    // 第一轮：工具循环回合（模型 → 工具调用 → 工具结果 → 模型总结）
    // 回合结局由 sealed TurnResult 承载：Completed / Failed / Aborted / IdleTimeout
    val firstTurn: TurnResult = okia.send("帮我计算 (1+2)*3，再搜索一下今天北京的天气") { /* 事件已走 events 流 */ }

    // 第二轮：历史已累积第一轮全部消息（User / Assistant / ToolResult），
    // 库把整个历史喂给模型，UI 直接渲染 history 即可
    okia.send("那 2+2 呢？") { /* 事件已走 events 流 */ }

    // ── 树的回退能力：修改第二轮问题（回退到第二轮 User 的前一条再重发）────
    // 改第一条消息 = 新建实例（§5.1）；rewind 回退到已存在条目，
    // entryId 不存在时抛 IllegalArgumentException，位置语义不校验。
    val secondTurnIdx: Int = okia.conversation.value.history
        .indexOfLast { it.message is Message.User }
    okia.rewind(okia.conversation.value.history[secondTurnIdx - 1].id)
    okia.send("改问：2*9 等于几？") { /* 事件已走 events 流 */ }

    // ── 持久化：导出快照 → codec 编码 → 存储（位置由 host 决定）────────
    // 恢复 = 重新 open(restore = snapshot)（协议由 host 重新提供，§5.7）
    // 分支语义由下游自行实现：export() 导出快照后 open(restore = snapshot)
    val snapshot: SessionSnapshot = okia.export()

    // ── 收尾 ──────────────────────────────────────────────────────────────
    uiJob.cancelAndJoin()
    eventJob.cancelAndJoin()
    okia.close()
}

// ════════════════════════════════════════════════════════════════════════════
// 伪 Compose UI：以下函数把 Conversation 快照映射成 UI 树（终端渲染模拟）
// ════════════════════════════════════════════════════════════════════════════

/** 渲染一帧：完整快照 → 整屏（Compose 中 = recomposition）。 */
private fun renderFrame(snapshot: Conversation) {
    clearScreen()
    println("── 会话 ${snapshot.id} ──")

    // LazyColumn：history 逐条渲染
    snapshot.history.forEach { entry -> renderEntry(entry, snapshot.history) }

    // 打字机区：live 是正在流式、尚未成条的助手消息
    snapshot.live?.let { live ->
        println("── 流式中 ──")
        println(renderBlocks(live.content) + "▌")
    }

    // turn 边界是下游封装（库不提供分组）：演示按 Message.User 切分
    println("── ${groupByTurns(snapshot.history).size} 个回合 ──")
}

/** 单条消息渲染。工具块的 Running/Outcome 在这里推导。 */
private fun renderEntry(entry: MessageEntry, history: List<MessageEntry>) {
    when (val message = entry.message) {
        is Message.User -> println("🙋 ${renderBlocks(message.content)}")

        is Message.Assistant -> message.message.content.forEach { block ->
            when (block) {
                is ContentBlock.Text -> println("🤖 ${block.text}")
                is ContentBlock.Thinking -> println("💭 ${block.text}")
                is ContentBlock.ToolCall -> {
                    // 状态推导：按 callId 配对后续 ToolResult。
                    // 配到 = Outcome（终态）；没配到 = Running（执行中）。
                    val outcome: ToolCallOutcome? = findOutcome(block.id, history)
                    if (outcome == null) {
                        println("🔄 [${block.name}] 执行中…")   // Running
                    } else {
                        println("🔧 [${block.name}] ${renderOutcome(outcome)}")  // Outcome
                    }
                }
                is ContentBlock.Image -> println("[图片 ${block.mimeType}]")
            }
        }

        is Message.ToolResult -> println("  ↳ ${renderOutcome(message.outcome)}")
    }
}

/** 工具终态配对：history 里 callId 对应的 ToolResult.outcome；无 = 执行中。 */
private fun findOutcome(callId: String, history: List<MessageEntry>): ToolCallOutcome? =
    history.asSequence()
        .map { it.message }
        .filterIsInstance<Message.ToolResult>()
        .firstOrNull { it.callId == callId }
        ?.outcome

/** turn 边界封装：按 Message.User 切分（库不提供，下游自行封装）。 */
private fun groupByTurns(history: List<MessageEntry>): List<List<MessageEntry>> {
    val turns = mutableListOf<MutableList<MessageEntry>>()
    for (entry in history) {
        if (entry.message is Message.User) turns += mutableListOf(entry)
        else turns.lastOrNull()?.add(entry)
    }
    return turns
}

/** 宿主 IPC 事件处理（RenderFrame 流式回调形态）。 */
private suspend fun onTurnEvent(event: TurnEvent) {
    when (event) {
        is TurnEvent.TurnStarted -> println("[事件] 回合开始")
        is TurnEvent.TextDelta -> println("[事件] 文本 delta: ${event.delta}")
        is TurnEvent.ToolCallDelta -> println("[事件] 工具参数 delta: ${event.delta}")
        is TurnEvent.ToolCallReady -> println("[事件] 工具调用参数就绪: ${event.toolCall.name}")
        is TurnEvent.ToolRunning -> println("[事件] 工具执行中: ${event.toolCall.name}")
        is TurnEvent.ToolSucceeded -> println("[事件] 工具成功: ${event.toolCall.name}")
        is TurnEvent.ToolFailed -> println("[事件] 工具失败: ${event.toolCall.name}")
        is TurnEvent.TurnCompleted -> println("[事件] 回合完成: ${event.message.stopReason}")
        is TurnEvent.TurnFailed -> println("[事件] 回合失败: ${event.error}")
        is TurnEvent.TurnAborted -> println("[事件] 回合取消: ${event.cause}")
        is TurnEvent.TurnIdleTimeout -> println("[事件] 回合 idle 超时")
        else -> Unit // Thinking*/RetryScheduled 等略
    }
}

/** live 是完整 partial 快照，UI 无需累积 delta 即可渲染打字机。 */
private fun renderLive(live: AssistantMessage): String = renderBlocks(live.content)

// ════════════════════════════════════════════════════════════════════════════
// 工具与 hook（骨架占位）
// ════════════════════════════════════════════════════════════════════════════

/** 工具 1：四则运算。 */
class CalculatorTool : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = TODO()

    companion object {
        val descriptor = ToolDescriptor(
            name = "calculator",
            description = "四则运算",
            inputSchemaJson = null,
            kind = ToolKind.Local
        )
    }
}

/** 工具 2：网络搜索。 */
class SearchTool : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = TODO()

    companion object {
        val descriptor = ToolDescriptor(
            name = "web_search",
            description = "网络搜索",
            inputSchemaJson = null,
            kind = ToolKind.Local
        )
    }
}

/** 1 个 hook：工具调用审计。 */
class AuditHook : Hooks {

    override suspend fun beforeToolCall(call: ToolCallHolder): Unit = TODO()

    override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder): Unit = TODO()
}

// 清屏：ANSI 控制序列（跨平台终端）
private fun clearScreen() {
    print("\u001b[2J\u001b[H")
}

// 内容块渲染：文本 / 思考 / 图像 / 工具调用
private fun renderBlocks(blocks: List<ContentBlock>): String =
    blocks.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Thinking -> "thinking: ${block.text}"
            is ContentBlock.Image -> "[image:${block.mimeType}]"
            is ContentBlock.ToolCall -> "tool(${block.name}): ${block.argumentsJson}"
        }
    }

// 工具结果渲染：5 态终态
private fun renderOutcome(outcome: ToolCallOutcome): String = when (outcome) {
    is ToolCallOutcome.Success -> "success: ${outcome.content}"
    is ToolCallOutcome.Failure -> "failure: ${outcome.message}"
    is ToolCallOutcome.Intercepted -> "intercepted: ${outcome.reason}"
    is ToolCallOutcome.Interrupted -> "interrupted: ${outcome.content ?: ""}"
    is ToolCallOutcome.Unknown -> "unknown: ${outcome.message}"
}
