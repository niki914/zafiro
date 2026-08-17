package com.niki914.okia.mcp

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolExecutor

/**
 * MCP 工具的 ToolExecutor。经 descriptor 的服务器名路由；servers 解析器
 * 返回当前配置，配置更新保持可见。具体实现在 M1 与 HTTP-only 客户端
 * 一起落地，槽位现在声明。
 * Design source: codex 工具执行，kai PRD §4.5 / §2；okia 骨架对照基线。
 */
class McpExecutor(
    private val client: McpClient,
    private val servers: (serverName: String) -> McpServer?
) : ToolExecutor {

    // 执行 MCP 工具调用；永不抛异常，总是产出工具结果。
    // MCP 推迟 T9：实现随 McpClient 落地一起交付，当前不实现。
    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    // 中断判定：从内部状态判断调用是否已运行。
    // MCP 推迟 T9：实现随 McpClient 落地一起交付，当前不实现。
    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = TODO()
}
