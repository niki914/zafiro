package com.niki914.okia

import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.protocol.ProtocolCompatMapper

/**
 * 依赖装配点。open() 从 config 加默认值构建；测试用 fake 构建，
 * 所有依赖 JVM 可替换。Clock / ForceStopHook 已删除（标准替代 / 并入 Hooks）；
 * toolRegistry 不在此处：单一来源是 OkiaConfig.toolRegistry（CR 第四轮裁决）。
 * conversation 不在此处：RealConversation 是内部实现，由门面在 open 时创建。
 * Design source: okia 骨架 OkiaDependencies；测试性要求来自 kai PRD。
 */
interface OkiaDependencies {

    // 回合驱动（LLM ↔ 工具循环）
    val agentLoop: AgentLoop

    // 协议边界（协议无关 → Provider 序列化，含兼容事实）。
    // open(protocol) 时经 ProtocolCompatMapper.from 构造；测试直接注入。
    val protocolMapper: ProtocolCompatMapper

    // MCP 客户端
    val mcpClient: McpClient
}
