package com.niki914.okia.hooks

import com.niki914.okia.transport.HttpRequest

/**
 * beforeRequest / afterRequest 的可改数据载体：Provider 请求。
 * 骨架期只声明字段，write 留空（没有消费者，不设计 API）。
 * Design source: pi extensions mutation。
 */
class HttpRequestHolder(
    val request: HttpRequest
) {

    // 最后写入者签名（write 记录）
    val lastWriter: String? = null

    // 改写请求并记录签名
    fun write(request: HttpRequest, signature: String): Unit = TODO()
}
