package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 实现一：legacy 2025-06-18 streamable HTTP 客户端。
 * discoverTools = initialize 握手 → initialized 通知 → tools/list 分页循环；
 * callTool = tools/call（无握手，无状态模式）。
 * 与实现二（DiscoveryStreamableHttpMcpClient）各自独立，只共享 McpWire
 * 线缆过程（D21 模式：一个协议一个实现，不复用超集层）。
 * Design source: MCP 2025-06-18；codex rmcp-client（legacy lifecycle）。
 */
internal class LegacyStreamableHttpMcpClient(
    engine: HttpEngine,
    timeouts: HttpTimeouts = McpWire.DEFAULT_MCP_TIMEOUTS
) : McpClient {

    private val wire = McpWire(engine, timeouts)

    override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
        // 会话终止自愈（2025-06-18 §Session Management）：服务器重启 / 会话过期后，
        // 携带旧会话的请求（含 initialize）会 404 或 -32000；按规范丢弃旧会话后
        // 以全新 initialize（不带旧 session id）重试一次，避免需要重建整个 Okia 实例。
        var retried = false
        while (true) {
            try {
                return handshakeTools(server)
            } catch (e: McpProtocolException) {
                if (!retried && McpWire.isSessionTerminated(e)) {
                    retried = true
                    wire.discardSession(server.name)
                    continue
                }
                throw e
            }
        }
    }

    override suspend fun callTool(
        server: McpServer,
        toolName: String,
        argumentsJson: String
    ): McpCallResult {
        // 会话失效自愈：丢弃旧会话 → 重建（initialize + initialized 通知）→ 重试一次。
        // 无状态服务器不返回会话头、tools/call 无握手也不报错，本路径不触发。
        var retried = false
        while (true) {
            try {
                val arguments = wire.parseArguments(toolName, argumentsJson)
                val result = wire.request(
                    server,
                    "tools/call",
                    buildJsonObject {
                        put("name", toolName)
                        put("arguments", arguments)
                    },
                    wire.nextId()
                )
                return wire.parseCallResult(result)
            } catch (e: McpProtocolException) {
                if (!retried && McpWire.isSessionTerminated(e)) {
                    retried = true
                    wire.discardSession(server.name)
                    handshake(server)
                    continue
                }
                throw e
            }
        }
    }

    // 完整握手 + tools/list；discoverTools 单独暴露（含会话终止重试）
    private suspend fun handshakeTools(server: McpServer): List<McpDiscoveredTool> {
        handshake(server)
        return wire.listTools(server)
    }

    // 重建会话：initialize 握手 + initialized 通知（不带旧会话头；2025-06-18：
    // 会话终止后 MUST 以新 initialize 建立新会话，不再附加失效 ID）
    private suspend fun handshake(server: McpServer) {
        wire.request(
            server,
            "initialize",
            buildJsonObject {
                put("protocolVersion", LEGACY_VERSION)
                put("capabilities", buildJsonObject {})
                put("clientInfo", McpWire.clientInfo())
            },
            wire.nextId()
        )
        wire.notify(server, "notifications/initialized")
    }

    companion object {

        /** legacy 协议版本（negotiate 时发送）。 */
        const val LEGACY_VERSION = "2025-06-18"
    }
}