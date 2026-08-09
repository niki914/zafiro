package com.niki914.okia.message

import kotlinx.serialization.Serializable

/**
 * 会话历史中的一条消息。三种具体角色，无泛化 role + content 基类。
 * Design source: pi（packages/ai types.ts）UserMessage / AssistantMessage / ToolResultMessage。
 */
@Serializable
sealed interface Message {

    /** 用户输入。内容块支持未来图像输入。 */
    @Serializable
    data class User(
        val content: List<ContentBlock>,
        val timestamp: Long
    ) : Message

    /** 助手响应，携带完整消息对象。 */
    @Serializable
    data class Assistant(val message: AssistantMessage) : Message

    /**
     * 反馈给模型的工具执行结果。内容在 outcome 内部，未产出结果的调用
     * （interrupted / unknown）在会话重载后仍可区分。
     */
    @Serializable
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val outcome: ToolCallOutcome
    ) : Message
}

/**
 * 完整助手响应状态。流式事件以部分快照形式发出，消费者无需累积 delta。
 * Design source: pi（packages/ai types.ts）AssistantMessage。
 */
@Serializable
data class AssistantMessage(
    val content: List<ContentBlock>,
    val stopReason: StopReason = StopReason.Pending,
    val usage: Usage? = null,
    val responseModel: String? = null,
    val reasoningSignature: String? = null
)
