package com.niki914.okia.hooks

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolDescriptor

/**
 * beforeToolCall 的可改数据载体：待执行的工具调用。
 * outcome 为阻断机制（开放问题 6.1 候选 A：全 Unit + holder 预留
 * outcome 字段，统一 mutation）。
 * write 实现（T5）：argumentsJson / outcome / lastWriter 为 private set 属性；
 * write 改参数、writeOutcome 写入阻断结果，均记录签名。
 * 落点 = 工具执行（T6 接入：write 改写执行参数；writeOutcome 短路执行并
 * 阻断后续 beforeToolCall hook，对齐 pi tool_call block 语义）。
 * Design source: pi extensions mutation。
 */
class ToolCallHolder(
    val id: String,
    val name: String,
    initialArgumentsJson: String,
    val descriptor: ToolDescriptor
) {

    // 当前参数 JSON（write 后为改写值）；private set 保持只读暴露
    var argumentsJson: String = initialArgumentsJson
        private set

    // 预留 outcome 字段：写入后短路执行（阻断）；未写入为 null
    var outcome: ToolCallOutcome? = null
        private set

    // 最后写入者签名（write 记录）；未写入为 null
    var lastWriter: String? = null
        private set

    // 改写参数 JSON 并记录签名
    fun write(argumentsJson: String, signature: String) {
        this.argumentsJson = argumentsJson
        lastWriter = signature
    }

    // 写入拦截结果（阻断）并记录签名
    fun writeOutcome(outcome: ToolCallOutcome, signature: String) {
        this.outcome = outcome
        lastWriter = signature
    }
}
