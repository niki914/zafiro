package com.niki914.okia.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 默认客户端：自动探测协议版本，把实现一（legacy）与实现二
 * （2026）串联。每服务器探测一次（先 server/discover，-32601 回退
 * legacy），结果按 server.name 缓存；探测失败不缓存（下次重试）。
 * 服务器名不再做字符集校验（D3 裁决）：线缆名已与原始名分离，由
 * ToolWireName 对服务器名与工具名统一规范化，无需在此约束原始名。
 * Design source: codex McpProtocolMode（会话级模式选择）的按服务器
 * 形态；okia G1 裁决（三实现：两协议类 + 探测包装类）。
 */
internal class AutoDetectMcpClient(
    private val legacy: LegacyStreamableHttpMcpClient,
    private val discovery: DiscoveryStreamableHttpMcpClient
) : McpClient {

    private val modeByServer = HashMap<String, McpMode>()
    private val modeMutex = Mutex()

    private suspend fun modeFor(server: McpServer): McpMode = modeMutex.withLock {
        modeByServer[server.name]?.let { return it }
        val mode = if (discovery.probe(server)) McpMode.Discovery else McpMode.Legacy
        modeByServer[server.name] = mode
        mode
    }

    override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> =
        when (modeFor(server)) {
            McpMode.Discovery -> discovery.discoverTools(server)
            McpMode.Legacy -> legacy.discoverTools(server)
        }

    override suspend fun callTool(
        server: McpServer,
        toolName: String,
        argumentsJson: String
    ): McpCallResult = when (modeFor(server)) {
        McpMode.Discovery -> discovery.callTool(server, toolName, argumentsJson)
        McpMode.Legacy -> legacy.callTool(server, toolName, argumentsJson)
    }
}