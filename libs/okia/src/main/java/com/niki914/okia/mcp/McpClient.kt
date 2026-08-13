package com.niki914.okia.mcp

/**
 * MCP 线缆客户端：在服务器上发现工具并执行调用。具体 HTTP 客户端在 M1，
 * 此接口让 loop 不接触任何 MCP 传输知识。仅 HTTP 传输。
 * Design source: codex（protocol/src/mcp.rs），kai PRD §2 / §5。
 */
interface McpClient {

    // 发现服务器上的工具
    suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> = TODO()

    // 调用服务器上的工具。返回结构化结果：isError 区分工具执行错误
    // （MCP 规范：成功 JSON-RPC result 内 isError=true）与正常成功；
    // content 保留多个 content block（当前仅文本）。
    suspend fun callTool(server: McpServer, toolName: String, argumentsJson: String): McpCallResult = TODO()
}

/** 一次 MCP 工具调用的结构化结果，承载工具执行错误与多个 content block。 */
data class McpCallResult(
    val isError: Boolean,
    val content: List<McpContentBlock>
)

/**
 * MCP 工具结果里的一个 content block。仅文本；结构化内容（Image / Resource /
 * Audio 等）暂不实现，M1 客户端遇到非文本 block 报错，未来需要时新增子类。
 */
sealed interface McpContentBlock {

    /** 文本块。 */
    data class Text(val text: String) : McpContentBlock
}

/** 在 MCP 服务器上发现的工具，喂入工具注册表。 */
data class McpDiscoveredTool(
    val name: String,
    val description: String?,
    val inputSchemaJson: String?
)
