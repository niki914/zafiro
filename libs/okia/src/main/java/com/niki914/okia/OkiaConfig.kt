package com.niki914.okia

import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpEngine

/**
 * 不可变连接配置。一次构建，变更通过 Okia.update 整体替换快照。
 * hooks 只读 List，builder 累积注册（开放问题 6.4 候选 A）。
 * Design source: okia 骨架 OkiaConfig 快照模式，删除 ConcurrencyMode / McpDiscoveryListener / JsonCodec。
 */
data class OkiaConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val temperature: Float,
    val maxTokens: Int,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val idleTimeoutSeconds: Long?,
    val headers: Map<String, String>,
    val retryPolicy: RetryPolicy,
    val mcpServers: List<McpServer>,
    val hooks: List<Hooks>,
    val toolRegistry: ToolRegistry?,
    val httpEngine: HttpEngine?
) {

    /** 可变构建器。build() 之后配置不可变。 */
    class Builder {
        var endpoint: String = ""
        var apiKey: String = ""
        var model: String = ""
        var temperature: Float = 0.7f
        var maxTokens: Int = 4096
        var connectTimeoutSeconds: Long = 30
        var readTimeoutSeconds: Long = 60
        var writeTimeoutSeconds: Long = 30
        var idleTimeoutSeconds: Long? = null
        var headers: Map<String, String> = emptyMap()
        var retryPolicy: RetryPolicy = RetryPolicy()
        var mcpServers: List<McpServer> = emptyList()
        var hooks: List<Hooks> = emptyList()
        var toolRegistry: ToolRegistry? = null
        var httpEngine: HttpEngine? = null

        // 组装不可变配置快照
        fun build(): OkiaConfig = TODO()
    }
}
