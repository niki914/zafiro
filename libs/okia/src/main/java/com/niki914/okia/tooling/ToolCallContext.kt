package com.niki914.okia.tooling

import com.niki914.okia.conversation.Conversation

/**
 * 流经执行的一条工具调用的不可变视图。descriptor 携带注册的 kind，
 * executor 无需重新解析注册表即可路由。attempt 从 1 起，重试时递增。
 * Design source: kai PRD §4.5 ToolCallContext；okia 骨架对照基线。
 */
data class ToolCallContext(
    val id: String,
    val name: String,
    val descriptor: ToolDescriptor,
    val argumentsJson: String,
    val attempt: Int,
    val conversation: Conversation?
)
