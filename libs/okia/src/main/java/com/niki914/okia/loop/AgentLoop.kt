package com.niki914.okia.loop

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
 * 消息产出通过 LoopRequest.onCommit 即时提交（消息级，facade 注入），
 * 停止为强制式：取消运行回合的协程传播到每个挂起点（流收集、工具执行、
 * 重试延迟）。run 不抛 CancellationException，取消以 FinishReason.Aborted
 * 呈现，取消源由 Okia 协调器记录（StopCause）。beforeStop 钩子不在这里调用：
 * 协调器在取消 job 前运行它（kill-then-stop）。
 * Design source: pi agentLoop, codex run_turn; okia 骨架 AgentLoop 对照基线。
 */
interface AgentLoop {

    // 跑完一个回合；消息已随 onCommit 提交，此处只返回结局
    suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult = TODO()
}

/**
 * 一回合的结局。消息不在此处返回：已随 LoopRequest.onCommit 逐条提交
 * （消息级原子：每条消息是完整单元；取消时待决工具调用以终态结果补全，
 * 历史完整）。cause 在回合被取消时设置；正常结束为 null。
 * Design source: okia 骨架 TurnResult 收敛（CR #2 裁决）。
 */
data class TurnResult(
    val reason: FinishReason,
    val cause: StopCause? = null
)

/**
 * 一次回合执行的不可变输入。idleTimeoutSeconds 只约束模型流：任何到达帧
 * 重置计时器，工具执行时间不计入。history 包含当前输入（send 已先提交
 * User 消息）；onCommit 是消息产出通道，由 Okia 协调器注入，内部在
 * RealConversation 的同一把 Mutex 下批量追加（原子）。
 */
data class LoopRequest(
    val snapshot: RequestSnapshot,
    val history: List<Message>,
    val input: String,
    val options: LoopOptions,
    val idleTimeoutSeconds: Long?,
    val toolRegistry: ToolRegistry,
    val protocolMapper: ProtocolCompatMapper,
    val hooks: List<Hooks>,
    val onCommit: suspend (List<Message>) -> Unit
)
