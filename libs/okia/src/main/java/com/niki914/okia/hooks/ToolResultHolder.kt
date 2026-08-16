package com.niki914.okia.hooks

import com.niki914.okia.message.ToolCallOutcome

/**
 * afterToolCall 的可改数据载体：工具执行结果。
 * write 实现（T5）：outcome / lastWriter 为 private set 属性；write 改值并
 * 记录签名。
 * 落点 = 工具结果回喂模型（T6 接入：编码前用 holder.outcome，hook 可替换
 * 结果负载，对齐 pi tool_result 改写能力）。
 * Design source: pi extensions mutation。
 */
class ToolResultHolder(
    initialOutcome: ToolCallOutcome
) {

    // 当前工具结果（write 后为改写值）；private set 保持只读暴露
    var outcome: ToolCallOutcome = initialOutcome
        private set

    // 最后写入者签名（write 记录）；未写入为 null
    var lastWriter: String? = null
        private set

    // 改写工具结果并记录签名
    fun write(outcome: ToolCallOutcome, signature: String) {
        this.outcome = outcome
        lastWriter = signature
    }
}
