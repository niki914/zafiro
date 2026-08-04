package com.niki914.s3ss10n.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HttpEngine {
    /**
     * 发起一次流式请求，返回原始 HTTP 响应行的 Flow。
     * SSE 解析由 SseLineParser 在上层完成。
     */
    fun stream(request: HttpRequest): Flow<String>

    /**
     * 发起一次请求，返回传输层响应帧。
     * 默认实现保持兼容，具体引擎可覆盖以按 Content-Type 拆分 SSE 事件。
     */
    fun frames(request: HttpRequest): Flow<HttpFrame> = stream(request).map { HttpFrame.Text(it) }

    /**
     * 发起一次非流式请求，返回原始响应体。
     */
    suspend fun unary(request: HttpRequest): String

    /**
     * 释放底层资源（连接池、线程池等）；幂等
     */
    fun close()
}
