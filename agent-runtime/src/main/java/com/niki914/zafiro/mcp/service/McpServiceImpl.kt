package com.niki914.zafiro.mcp.service

import com.niki914.logging.Logger
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class McpServiceImpl(
    private val toolAdapter: McpToolAdapter = McpToolAdapter(),
    private val configProvider: suspend () -> RuntimeMcpServiceConfig,
) : McpService {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpService"
    }

    private val lifecycleMutex = Mutex()
    private var serverEngine: EmbeddedServer<*, *>? = null

    private val _status = MutableStateFlow<McpServiceStatus>(McpServiceStatus.Stopped)
    override val status: StateFlow<McpServiceStatus> = _status.asStateFlow()

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

    private fun startInternal(config: RuntimeMcpServiceConfig) {
        _status.value = McpServiceStatus.Starting
        Logger.i(
            LOG_TAG,
            "starting MCP server on ${config.host}:${config.port} (tokenConfigured=${config.bearerToken.isNotBlank()})"
        )
        // Ktor 引擎的 accept 循环跑在自己的 CoroutineScope 里，绑定失败会绕过这里的 try/catch
        // 变成未捕获异常，落到 Thread.uncaughtExceptionHandler（release 下是 CrashRecorder）直接杀进程。
        val engineScope = CoroutineScope(
            CoroutineExceptionHandler { _, throwable ->
                Logger.e(LOG_TAG, "MCP server engine failed: ${throwable.message}", throwable)
                _status.value = McpServiceStatus.Error(
                    message = throwable.message ?: "MCP server engine failed",
                    stackTrace = throwable.stackTraceToString(),
                )
            }
        )
        try {
            val engine = engineScope.embeddedServer(CIO, port = config.port, host = config.host) {
                // Bearer Token 拦截验证：当且仅当配置了非空 token 时才强制校验；留空则直接放行
                intercept(ApplicationCallPipeline.Plugins) {
                    val expectedToken = config.bearerToken.trim()
                    if (expectedToken.isNotEmpty()) {
                        val authHeader = call.request.header(HttpHeaders.Authorization)?.trim()
                        val expectedHeader = "Bearer $expectedToken"
                        if (authHeader == null || authHeader != expectedHeader) {
                            Logger.w(
                                LOG_TAG,
                                "unauthorized MCP request rejected: missing or invalid Bearer token"
                            )
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
            _status.value = McpServiceStatus.Running(config.host, config.port)
            Logger.i(
                LOG_TAG,
                "MCP server successfully started and listening on http://${config.host}:${config.port}/mcp"
            )
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Logger.e(LOG_TAG, "failed to start MCP server: ${cause.message}", cause)
            _status.value = McpServiceStatus.Error(
                message = cause.message ?: "Failed to start server",
                stackTrace = cause.stackTraceToString(),
            )
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
            _status.value = McpServiceStatus.Stopped
            Logger.i(LOG_TAG, "MCP server stopped")
        }
    }
}
