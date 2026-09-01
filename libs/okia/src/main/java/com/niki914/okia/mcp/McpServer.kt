package com.niki914.okia.mcp

import com.niki914.okia.transport.redactHeaders
import com.niki914.okia.transport.redactUrl

/**
 * MCP 服务器配置。传输 sealed，未来扩展传输不触碰 loop。仅 HTTP；
 * 本地进程与 Node 传输不在 Android / JVM 范围。
 * Design source: okia 骨架 McpServer（Zafiro 使用验证的配置形态）。
 */
data class McpServer(
    val name: String,
    val transport: McpTransport,
    val headers: Map<String, String>,
    val enabled: Boolean
) {

    // headers 敏感值脱敏（如 MCP 服务器认证头）
    override fun toString(): String =
        "McpServer(name=$name, transport=$transport, headers=${redactHeaders(headers)}, " +
                "enabled=$enabled)"
}

/** 客户端如何到达一台 MCP 服务器。 */
sealed interface McpTransport {

    /** HTTP 传输；具体帧格式（streamable 或 SSE）是 M1 客户端细节。 */
    data class Http(val url: String) : McpTransport {

        // URL query 值脱敏（签名参数不泄漏）
        override fun toString(): String = "Http(url=${redactUrl(url)})"
    }
}
