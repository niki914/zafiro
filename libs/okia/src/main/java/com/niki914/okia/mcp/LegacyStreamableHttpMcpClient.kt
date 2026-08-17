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
        // 1. initialize 握手：服务端确认 protocolVersion / capabilities / serverInfo
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
        // 2. initialized 通知（无响应体；2xx 即成功）
        wire.notify(server, "notifications/initialized")
        // 3. tools/list 分页循环
        return wire.listTools(server)
    }

    override suspend fun callTool(
        server: McpServer,
        toolName: String,
        argumentsJson: String
    ): McpCallResult {
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
    }

    companion object {

        /** legacy 协议版本（negotiate 时发送）。 */
        const val LEGACY_VERSION = "2025-06-18"
    }
}