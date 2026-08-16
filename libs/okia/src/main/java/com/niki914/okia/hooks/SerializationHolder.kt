package com.niki914.okia.hooks

import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot

/**
 * beforeSerialization 的可改数据载体：协议无关的请求输入。
 * write 实现（T5）：snapshot / history / lastWriter 为 private set 属性；
 * write 改值并记录签名，多次 write 后者覆盖。
 * 落点 = buildRequest 输入（RealAgentLoop 直接用 holder 的 snapshot /
 * history 构建请求，数据脱敏主战场，§5.9.4）。
 * Design source: pi extensions mutation。
 */
class SerializationHolder(
    initialSnapshot: RequestSnapshot,
    initialHistory: List<Message>
) {

    // 当前请求输入快照（write 后为改写值）；private set 保持只读暴露
    var snapshot: RequestSnapshot = initialSnapshot
        private set

    // 当前历史（write 后为改写值）
    var history: List<Message> = initialHistory
        private set

    // 最后写入者签名（write 记录）；未写入为 null
    var lastWriter: String? = null
        private set

    // 改写请求输入并记录签名（一次性，发完即弃，不写回会话树）
    fun write(
        snapshot: RequestSnapshot,
        history: List<Message>,
        signature: String
    ) {
        this.snapshot = snapshot
        this.history = history
        lastWriter = signature
    }
}
