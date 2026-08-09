package com.niki914.okia.mcp

/**
 * MCP 服务器配置。传输 sealed，未来扩展传输不触碰 loop。仅 HTTP；
 * 本地进程与 Node 传输不在 Android / JVM 范围。
 * Design source: okia 骨架 McpServer（Nexus 使用验证的配置形态）。
 */
data class McpServer(
    val name: String,
    val transport: McpTransport,
    val headers: Map<String, String>,
    val enabled: Boolean
)

/** 客户端如何到达一台 MCP 服务器。 */
sealed interface McpTransport {

    /** HTTP 传输；具体帧格式（streamable 或 SSE）是 M1 客户端细节。 */
    data class Http(val url: String) : McpTransport
}
