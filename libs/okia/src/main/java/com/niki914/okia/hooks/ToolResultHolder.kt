package com.niki914.okia.hooks

import com.niki914.okia.message.ToolCallOutcome

/**
 * afterToolCall 的可改数据载体：工具执行结果。
 * 骨架期只声明字段，write 留空（没有消费者，不设计 API）。
 * Design source: pi extensions mutation。
 */
class ToolResultHolder(
    val outcome: ToolCallOutcome
) {

    // 最后写入者签名（write 记录）
    val lastWriter: String? = null

    // 改写工具结果并记录签名
    fun write(outcome: ToolCallOutcome, signature: String): Unit = TODO()
}
