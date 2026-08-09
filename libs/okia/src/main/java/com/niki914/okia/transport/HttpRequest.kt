package com.niki914.okia.transport

/**
 * ChatProtocol 构建、HttpEngine 执行的不可变 HTTP 请求。
 * Design source: okia 骨架 HttpRequest。
 */
data class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeouts: HttpTimeouts
)

/** 毫秒级超时值。 */
data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long
)
