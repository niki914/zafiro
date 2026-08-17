package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * MCP 实现二：2026-07-28 无状态化客户端（minimal）。
 * discoverTools = server/discover（版本协商 + 能力确认）→ tools/list 分页；
 * callTool = tools/call。每请求携带自包含元数据（_meta 内嵌 protocolVersion
 * 与 clientInfo，codex "self-contained request metadata" 同构）。
 * 未实现（推迟，低 ROI）：OAuth 发现 / elicitation / 8MB 消息上限 / ttlMs
 * 缓存语义、partial 流式工具结果。未来按需在同类内扩展。
 * Design source: MCP 2026-07-28（codex rmcp-client mcp_2026_* 测试实证
 * 的 wire 形态：params._meta.io.modelcontextprotocol/protocolVersion）。
 */
internal class DiscoveryStreamableHttpMcpClient(
    engine: HttpEngine,
    timeouts: HttpTimeouts = McpWire.DEFAULT_MCP_TIMEOUTS
) : McpClient {

    private val wire = McpWire(engine, timeouts)

    /**
     * 探测：服务器是否接受 2026-07-28 协议。发一次 server/discover：
     * 返回 result → true；JSON-RPC -32601（MethodNotFound，服务器不认识
     * 该方法）→ false（调用方回退 legacy）；其他错误原样抛。
     */
    suspend fun probe(server: McpServer): Boolean {
        val result = try {
            wire.request(
                server,
                "server/discover",
                buildJsonObject { put("_meta", modernMeta()) },
                wire.nextId()
            )
        } catch (e: McpProtocolException) {
            if (e.jsonRpcCode == McpWire.JSONRPC_METHOD_NOT_FOUND) return false
            // 有状态服务器对 server/discover 返回 -32000 "Server not initialized"（未先
            // initialize 会话）：不支持无前置握手的 2026 形态，回退 legacy。
            if (e.jsonRpcCode == -32000 &&
                e.message?.contains("not initialized", ignoreCase = true) == true
            ) {
                return false
            }
            throw e
        }
        // 服务器必须显式声明支持我们发起的版本（协商失败 = 明确失败）
        val supported = (result["supportedVersions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (DISCOVERY_VERSION !in supported) {
            throw McpProtocolException(
                "MCP server advertised supportedVersions=${supported.ifEmpty { "none" }}; " +
                    "it does not support $DISCOVERY_VERSION. (A server that accepts server/discover " +
                    "must list its supported versions; this shape is a protocol violation.)"
            )
        }
        return true
    }

    override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
        probe(server)  // 确认 + 版本协商（幂等；AutoDetect 可能已探测一次）
        return wire.listTools(server, modernMeta())
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
                put("_meta", modernMeta())
                put("name", toolName)
                put("arguments", arguments)
            },
            wire.nextId()
        )
        return wire.parseCallResult(result)
    }

    /** 自包含请求元数据：版本与客户端标识内嵌于 params._meta（2026 形态）。 */
    private fun modernMeta(): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", DISCOVERY_VERSION)
        put("io.modelcontextprotocol/clientInfo", McpWire.clientInfo())
    }

    companion object {

        /** 2026 协议版本。 */
        const val DISCOVERY_VERSION = "2026-07-28"
    }
}