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
 * 模型结束一条响应的原因。消息级，区别于回合级 FinishReason：
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
