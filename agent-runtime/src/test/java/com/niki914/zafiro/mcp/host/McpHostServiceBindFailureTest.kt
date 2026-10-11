package com.niki914.zafiro.mcp.host

import com.niki914.zafiro.api.McpHostStatus
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.RuntimeMcpHostConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

// 保护：MCP 引擎的 accept 循环跑在独立 CoroutineScope 里，端口被占用时必须只落成 Error 状态，
// 不能逃逸成未捕获异常——release 下那会被 CrashRecorder 直接杀进程。
class McpHostServiceBindFailureTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun occupiedPortYieldsErrorStatusInsteadOfUncaughtException() {
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val escaped = AtomicReference<Throwable?>(null)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> escaped.compareAndSet(null, error) }

        val service = McpHostServiceImpl(McpToolAdapter(BuiltinToolRegistry(emptyList()))) {
            RuntimeMcpHostConfig(enabled = true, port = occupied.localPort)
        }
        try {
            runBlocking { service.start() }
            // 未捕获异常在引擎的调度线程上异步送达，留一小段观察窗口
            Thread.sleep(500)

            assertNull("bind failure leaked to the uncaught exception handler", escaped.get())
            assertTrue(service.status.value is McpHostStatus.Error)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            runBlocking { service.stop() }
            occupied.close()
        }
    }
}
