package com.niki914.okai

import com.niki914.okai.codec.JsonCodec
import com.niki914.okai.error.RetryPolicy
import com.niki914.okai.mcp.McpDiscoveryListener
import com.niki914.okai.mcp.McpServer
import com.niki914.okai.session.ConcurrencyMode
import com.niki914.okai.transport.HttpEngine

/**
 * Immutable connection config. Built once, changed only through Okai.update,
 * which swaps the whole snapshot so no intermediate state is ever visible.
 * The loop freezes a round snapshot per segment, so retries reuse identical requests.
 *
 * Design source: independent design; snapshot pattern validated in the Nexus
 * usage of kai, made immutable per the kai PRD concurrency contract (section 4.4).
 */
data class OkaiConfig(
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
    val concurrencyMode: ConcurrencyMode,
    val retryPolicy: RetryPolicy,
    val mcpServers: List<McpServer>,
    val mcpDiscoveryListener: McpDiscoveryListener?,
    val jsonCodec: JsonCodec?,
    val httpEngine: HttpEngine?
) {
    /** Mutable builder. After build(), the config is immutable. */
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
        var concurrencyMode: ConcurrencyMode = ConcurrencyMode.Replace
        var retryPolicy: RetryPolicy = RetryPolicy()
        var mcpServers: List<McpServer> = emptyList()
        var mcpDiscoveryListener: McpDiscoveryListener? = null
        var jsonCodec: JsonCodec? = null
        var httpEngine: HttpEngine? = null

        fun build(): OkaiConfig = OkaiConfig(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
            writeTimeoutSeconds = writeTimeoutSeconds,
            idleTimeoutSeconds = idleTimeoutSeconds,
            headers = headers.toMap(),
            concurrencyMode = concurrencyMode,
            retryPolicy = retryPolicy,
            mcpServers = mcpServers.map { it.copy(headers = it.headers.toMap()) },
            mcpDiscoveryListener = mcpDiscoveryListener,
            jsonCodec = jsonCodec,
            httpEngine = httpEngine
        )
    }
}
