package com.niki914.okia.hooks

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolDescriptor

/**
 * beforeToolCall 的可改数据载体：待执行的工具调用。
 * outcome 为阻断机制预留（开放问题 6.1 候选 A：全 Unit + holder 预留
 * outcome 字段，统一 mutation）。
 * 骨架期只声明字段，write 留空（没有消费者，不设计 API）。
 * Design source: pi extensions mutation。
 */
class ToolCallHolder(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val descriptor: ToolDescriptor
) {

    // 预留 outcome 字段：写入后短路执行（阻断）
    val outcome: ToolCallOutcome? = null

    // 最后写入者签名（write 记录）
    val lastWriter: String? = null

    // 改写参数 JSON 并记录签名
    fun write(argumentsJson: String, signature: String): Unit = TODO()

    // 写入拦截结果（阻断）并记录签名
    fun writeOutcome(outcome: ToolCallOutcome, signature: String): Unit = TODO()
}
