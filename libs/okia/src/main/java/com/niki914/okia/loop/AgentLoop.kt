package com.niki914.okia.loop

import com.niki914.okia.error.LLMError
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpEngine

/**
 * 回合驱动：模型调用与工具执行循环，直到最后一条消息不是工具请求。
 * 消息产出通过 LoopRequest.onCommit 即时提交（消息级，facade 注入），
 * 停止为强制式：取消运行回合的协程传播到每个挂起点（流收集、工具执行、
 * 重试延迟）。run 不抛 CancellationException，取消以 TurnResult.Aborted
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
 * 历史完整）。sealed 让终态字段必带、非法状态不可表达：
 * Completed 必带 stopReason（只可能是 Stop / Length），Failed 必带 error
 * （RetryExhausted 经 LLMErrorCode.RetryExhausted 表达），Aborted 必带 cause，
 * IdleTimeout 无附加数据。
 * Design source: okia 骨架 TurnResult 收敛（CR 第四轮裁决）。
 */
sealed interface TurnResult {

    /** 回合正常结束。stopReason 为最终响应的结束原因，只可能是 Stop 或 Length。 */
    data class Completed(val stopReason: StopReason) : TurnResult

    /** 回合失败。error 必带，分类见 LLMErrorCode。 */
    data class Failed(val error: LLMError) : TurnResult

    /** 回合被取消。cause 必带（UserStop / External）。 */
    data class Aborted(val cause: StopCause) : TurnResult

    /** 模型流 idle 超时（框架检测，非 Provider 错误）。 */
    object IdleTimeout : TurnResult
}

/**
 * 一次回合执行的不可变输入。idleTimeoutSeconds 只约束模型流：检测点位于
 * 原始 SseLine 流（parseStream 之前），任何到达帧（含 keep-alive 的
 * null data）重置计时器，工具执行时间不计入。httpEngine 是回合唯一的
 * 传输入口（AgentLoop 必须经它发请求）；retryPolicy 为传输层重试策略
 * （Compat.retryableStatusCodes + 指数退避），回合层重试在 options。
 * history 包含当前输入（send 已先提交 User 消息）；onCommit 是消息产出
 * 通道，由 Okia 协调器注入，内部在 RealConversation 的同一把 Mutex 下
 * 批量追加（原子）。
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
    val httpEngine: HttpEngine,
    val retryPolicy: RetryPolicy,
    val onCommit: suspend (List<Message>) -> Unit
)
