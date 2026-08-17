package com.niki914.okia.transport

/**
 * SSE 聚合后的结构化事件。data 为 data 字段拼接内容（多行 \n 连接）；
 * 按 W3C 标准 data 缓冲为空字符串的事件不产出，故 data 非空字符串
 * （可能为纯空白）。
 * event 为事件类型：命名事件协议（Anthropic Messages、OpenAI Responses、
 * MCP 等）用它路由（Anthropic 的 content_block_* / message_delta、Responses
 * 的 response.*、MCP 的 message）；无命名事件协议（OpenAI 兼容 data-only
 * 流）不使用，为 null。
 * id / retry 为 SSE 重连机制字段，LLM 与 MCP 均不使用，不解析。
 * Design source: W3C HTML spec Server-Sent Events；codex sse_stream crate
 * （rmcp-client 读 event 字段过滤的实证）。
 */
data class SseEvent(
    val data: String,
    val event: String? = null
)
