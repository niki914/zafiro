package com.niki914.okia.loop

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.event.FinishReason
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.ToolRegistry

/**
 * 回合驱动：模型调用与工具执行循环，直到最后一条消息不是工具请求。
 * 停止为强制式：取消运行回合的协程传播到每个挂起点（流收集、工具执行、
 * 重试延迟）。run 不抛 CancellationException，取消以 FinishReason.Aborted
 * 呈现，取消源由 Okia 协调器记录（StopCause）。beforeStop 钩子不在这里调用：
 * 协调器在取消 job 前运行它（kill-then-stop）。
 * Design source: pi agentLoop, codex run_turn; okia 骨架 AgentLoop 对照基线。
 */
interface AgentLoop {

    // 跑完一个回合，返回要提交的消息增量
    suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult = TODO()
}

/**
 * 一整个回合的消息增量，按产生顺序，供调用方提交。回合交错助手消息与
 * 工具结果；取消时每个待决工具调用都有终态结果，提交的历史完整。
 * cause 在回合被取消时设置；正常结束为 null。
 */
data class TurnResult(
    val messages: List<Message>,
    val reason: FinishReason,
    val cause: StopCause? = null
)

/**
 * 一次回合执行的不可变输入。idleTimeoutSeconds 只约束模型流：任何到达帧
 * 重置计时器，工具执行时间不计入。conversation / toolRegistry /
 * protocolMapper / hooks 由 Okia 协调器装配。
 */
data class LoopRequest(
    val snapshot: RequestSnapshot,
    val history: List<Message>,
    val input: String,
    val options: LoopOptions,
    val idleTimeoutSeconds: Long?,
    val conversation: Conversation,
    val toolRegistry: ToolRegistry,
    val protocolMapper: ProtocolCompatMapper,
    val hooks: List<Hooks>
)
