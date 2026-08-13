package com.niki914.okia.transport

/**
 * 底层 HTTP 传输。与 LLM 协议解耦，测试可注入 fake engine。
 * 两个方法分工：stream 是模型流式请求，由 AgentLoop 调用并触发
 * beforeRequest / afterRequest hook；unary 是 MCP 等其他网络请求，
 * 不触发 hook。流式返回响应头加 body 行；错误保持结构化，不把 status
 * 拍平成消息串。
 * 取消契约：loop 在回合协程内收集 lines 流，取消收集关闭底层请求，
 * 停止的回合不泄漏 socket 或连接。
 * Design source: pi provider-retry、codex TransportError::Http，
 * kai PRD §4.4 / §4.7；okia 骨架对照基线。
 */
interface HttpEngine {

    // 模型流式请求：suspend 到响应头到达后返回。status / headers 先于 body 行
    // 可用，供 loop 做结构化重试决策；返回前可被协程取消，不阻塞线程。
    // 仅此路径触发 beforeRequest / afterRequest hook。
    suspend fun stream(request: HttpRequest): StreamResponse

    // 单次请求（MCP 等其他网络请求），不触发 hook。
    suspend fun unary(request: HttpRequest): HttpResponse

    // 释放引擎资源
    fun close(): Unit
}
