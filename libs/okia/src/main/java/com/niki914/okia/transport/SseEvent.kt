package com.niki914.okia.transport

/**
 * SSE 聚合后的结构化事件。data 为 data 字段拼接内容（多行 \n 连接）；
 * 按 W3C 标准 data 缓冲为空字符串的事件不产出，故 data 非空字符串
 * （可能为纯空白）。
 * event 为事件类型：MCP 等协议用它过滤（只处理 message），模型流
 * （OpenAI 兼容 / DeepSeek）不使用，为 null。
 * id / retry 为 SSE 重连机制字段，LLM 与 MCP 均不使用，不解析。
 * Design source: W3C HTML spec Server-Sent Events；codex sse_stream crate
 * （rmcp-client 读 event 字段过滤的实证）。
 */
data class SseEvent(
    val data: String,
    val event: String? = null
)
