package com.niki914.okia.hooks

import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot

/**
 * beforeSerialization 的可改数据载体：协议无关的请求输入。
 * 骨架期只声明字段，write 留空（没有消费者，不设计 API）。
 * Design source: pi extensions mutation。
 */
class SerializationHolder(
    val snapshot: RequestSnapshot,
    val history: List<Message>
) {

    // 最后写入者签名（write 记录）
    val lastWriter: String? = null

    // 改写请求输入并记录签名（一次性，发完即弃，不写回会话树）
    fun write(
        snapshot: RequestSnapshot,
        history: List<Message>,
        signature: String
    ): Unit = TODO()
}
