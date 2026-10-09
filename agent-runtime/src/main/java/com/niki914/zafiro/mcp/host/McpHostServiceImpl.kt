package com.niki914.zafiro.mcp.host

import com.niki914.logging.Logger
import com.niki914.zafiro.api.McpHostService
import com.niki914.zafiro.api.McpHostStatus
import com.niki914.zafiro.settings.model.RuntimeMcpHostConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class McpHostServiceImpl(
    private val toolAdapter: McpToolAdapter = McpToolAdapter(),
    private val configProvider: suspend () -> RuntimeMcpHostConfig,
) : McpHostService {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpHostService"
    }

    private val lifecycleMutex = Mutex()
    private var serverEngine: EmbeddedServer<*, *>? = null

    private val _status = MutableStateFlow<McpHostStatus>(McpHostStatus.Stopped)
    override val status: StateFlow<McpHostStatus> = _status.asStateFlow()

    override suspend fun start() {
        lifecycleMutex.withLock {
            val config = configProvider()
            if (!config.enabled) {
                Logger.i(LOG_TAG, "MCP server start skipped: config.enabled is false")
                return
            }
            if (serverEngine != null) {
                Logger.i(LOG_TAG, "MCP server already running on port ${config.port}")
                return
            }
            startInternal(config)
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            stopInternal()
        }
    }

    override suspend fun restart() {
        lifecycleMutex.withLock {
            stopInternal()
            val config = configProvider()
            if (config.enabled) {
                startInternal(config)
            }
        }
    }

    private fun startInternal(config: RuntimeMcpHostConfig) {
        _status.value = McpHostStatus.Starting
        Logger.i(LOG_TAG, "starting MCP server on ${config.host}:${config.port} (tokenConfigured=${config.bearerToken.isNotBlank()})")
        try {
            val engine = embeddedServer(CIO, port = config.port, host = config.host) {
                // Bearer Token 拦截验证：当且仅当配置了非空 token 时才强制校验；留空则直接放行
                intercept(ApplicationCallPipeline.Plugins) {
                    val expectedToken = config.bearerToken.trim()
                    if (expectedToken.isNotEmpty()) {
                        val authHeader = call.request.header(HttpHeaders.Authorization)?.trim()
                        val expectedHeader = "Bearer $expectedToken"
                        if (authHeader == null || authHeader != expectedHeader) {
                            Logger.w(LOG_TAG, "unauthorized MCP request rejected: missing or invalid Bearer token")
                            call.respondText(
                                text = "Unauthorized: Invalid or missing Bearer token",
                                status = HttpStatusCode.Unauthorized,
                            )
                            finish()
                            return@intercept
                        }
                    }
                }

                // 挂载官方 MCP Stateless Streamable HTTP 协议端点
                mcpStatelessStreamableHttp(
                    path = "/mcp",
                    enableDnsRebindingProtection = (config.host == "127.0.0.1"),
                ) {
                    toolAdapter.buildServer(config)
                }
            }
            engine.start(wait = false)
            serverEngine = engine
            _status.value = McpHostStatus.Running(config.host, config.port)
            Logger.i(LOG_TAG, "MCP server successfully started and listening on http://${config.host}:${config.port}/mcp")
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "failed to start MCP server: ${e.message}", e)
            _status.value = McpHostStatus.Error(e.message ?: "Failed to start server")
            serverEngine = null
        }
    }

    private fun stopInternal() {
        if (serverEngine != null) {
            Logger.i(LOG_TAG, "stopping MCP server")
            try {
                serverEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            } catch (e: Exception) {
                Logger.w(LOG_TAG, "error stopping server engine: ${e.message}")
            }
            serverEngine = null
            _status.value = McpHostStatus.Stopped
            Logger.i(LOG_TAG, "MCP server stopped")
        }
    }
}
