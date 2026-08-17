package com.niki914.okia.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 默认客户端：自动探测协议版本，把实现一（legacy）与实现二
 * （2026）串联。每服务器探测一次（先 server/discover，-32601 回退
 * legacy），结果按 server.name 缓存；探测失败不缓存（下次重试）。
 * 服务器名校验在此统一执行（G6：支持前缀拼接的字符集约束）。
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
        validateServerName(server.name)
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

    companion object {

        /** 服务器名字符集约束（G6 命名设计：支持 `${server}_${tool}` 拼接）。 */
        private val SERVER_NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,32}$")

        fun validateServerName(name: String) {
            if (!SERVER_NAME_REGEX.matches(name)) {
                throw McpProtocolException(
                    "invalid MCP server name '$name': must match $SERVER_NAME_REGEX " +
                        "(tool names are namespaced as \${serverName}_\${toolName})"
                )
            }
        }
    }
}