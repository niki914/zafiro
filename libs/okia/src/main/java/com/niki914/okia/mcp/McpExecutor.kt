package com.niki914.okia.mcp

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import kotlinx.coroutines.CancellationException

/**
 * MCP 工具的 ToolExecutor：经 descriptor 的服务器名路由；servers 解析器
 * 返回当前配置，配置热更新保持可见。
 *
 * 执行映射（T9b 定案，Q3）：
 * - 成功（isError=false）：Success(文本块换行拼接)。McpCallResult.content 是
 *   tools/call 的 JSON-RPC result 一次性给出的有序文本块数组（单次 unary
 *   往返，非流式），本库只有纯文本通道（ToolCallOutcome.content → 工具消息
 *   文本），块间以换行拼接。与 Codex 的差异：codex 保留 content 数组结构
 *   回喂模型（其协议支持结构化 content / image），本库收窄为纯文本
 *   （§8.8 #4），因此多块必须在此合并为单字符串。
 * - 工具自身错误（isError=true，协议内 result.isError 标志）：Failure(固定
 *   文案, 拼接文本)。MCP 规范中工具执行错误与成功同构装在 result 里、
 *   由 isError 区分；codex 同样把 isError=true 标为失败但保留 content。
 * - 协议/传输异常（McpProtocolException：JSON-RPC error / 网络失败 / 畸形
 *   响应）：Failure(message=异常文本, content=null)。传输层错误没有工具结果
 *   内容可回喂；错误文本进 message（事件/UI 可见）。
 * - 永不抛异常契约：除取消（CancellationException 传播，契约不吞）外，
 *   全部异常转 Failure outcome。
 *
 * 工具名还原：注册名与原始 MCP 工具名分离（见 ToolWireName）——executor 收
 * 到的 ToolCallContext.descriptor.name 即原始 MCP 工具名，直接用于 callTool，
 * 不再需要从线缆名剥前缀。call.name 是线缆名（`mcp__server__tool`），仅用于
 * 展示与事件；路由由 descriptor.kind.Mcp.serverName 承担。
 *
 * onInterrupt：HTTP 请求已发出后框架无法得知服务器是否已远程执行，返回
 * Unknown（「可能已远程执行，永不重试」语义）。调用点暂未接线（取消补全
 * 待有真实消费者时落地，docs §8.18），方法体先就绪。
 * Design source: kai PRD §4.5 / §2；okia 骨架对照基线；codex mcp_tool_call
 * is_error / Err 两分支映射。
 */
class McpExecutor(
    private val client: McpClient,
    private val servers: (serverName: String) -> McpServer?
) : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        return try {
            val serverName = (call.descriptor.kind as? ToolKind.Mcp)?.serverName
                ?: return ToolCallOutcome.Failure("not an MCP tool: ${call.name}")
            val toolName = call.descriptor.name // 原始 MCP 工具名，直接调用
            val server = servers(serverName)
                ?: return ToolCallOutcome.Failure("MCP server not found: $serverName")
            val result = client.callTool(server, toolName, call.argumentsJson)
            val content = result.content.joinToString("\n") { block ->
                when (block) {
                    // 非文本 block 已在 McpWire.parseCallResult 报错（§8.8 #4 收窄），
                    // 此处 only Text 可达。
                    is McpContentBlock.Text -> block.text
                }
            }
            if (result.isError) {
                ToolCallOutcome.Failure(
                    "tool returned isError=true",
                    content = content.ifEmpty { null })
            } else {
                ToolCallOutcome.Success(content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpProtocolException) {
            ToolCallOutcome.Failure(e.message ?: "MCP tool call failed")
        } catch (e: Exception) {
            // 兜底：client 实现违反契约抛非协议异常也转 outcome（永不抛异常）
            ToolCallOutcome.Failure("MCP tool call failed: ${e.message}")
        }
    }

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome =
        ToolCallOutcome.Unknown("MCP call may have executed remotely (request already dispatched)")
}