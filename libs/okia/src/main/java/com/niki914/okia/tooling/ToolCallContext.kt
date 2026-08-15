package com.niki914.okia.tooling

/**
 * 流经执行的一条工具调用的不可变视图。descriptor 携带注册的 kind，
 * executor 无需重新解析注册表即可路由。
 * 不携带对话上下文与重试计数：ToolExecutor 知道完整对话历史是越界，
 * 幂等性由 call id 承载（重试时 id 不变，工具自行记录已处理的 id）；
 * 需要会话归属信息的工具由 host 在注册时自行注入。
 * Design source: kai PRD §4.5 ToolCallContext；okia 骨架对照基线。
 */
data class ToolCallContext(
    val id: String,
    val name: String,
    val descriptor: ToolDescriptor,
    val argumentsJson: String
)
