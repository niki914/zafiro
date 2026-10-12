package com.niki914.okia.message

import kotlinx.serialization.Serializable

/**
 * 单条助手响应的 token 记账。
 * Design source: pi（packages/ai types.ts）Usage。
 */
@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val reasoningTokens: Long = 0
)

/**
 * 当前上下文占用：最近一次请求的输入总量（prompt 侧 token）。
 *
 * 口径由本库统一：三个协议都已把 inputTokens 归一为「非缓存输入」
 * （OpenAI 侧扣除 cached，Anthropic 侧本就不含缓存），所以总量
 * = inputTokens + cacheReadTokens + cacheWriteTokens。少算 cache 会偏小。
 *
 * 全 0 = 该次响应没有有效记账（中断、未上报），按无数据返回 null，
 * 消费方据此显示「暂无」而不是 0%。
 */
val Usage.contextTokens: Long?
    get() = (inputTokens + cacheReadTokens + cacheWriteTokens).takeIf { it > 0 }

/**
 * 模型结束一条响应的原因。消息级，区别于回合级 sealed TurnResult：
 * toolUse 是回合内的正常中间状态。
 * Design source: pi stopReason。
 */
@Serializable
enum class StopReason {
    Pending,
    Stop,
    Length,
    ToolUse,
    Error,
    Aborted
}
