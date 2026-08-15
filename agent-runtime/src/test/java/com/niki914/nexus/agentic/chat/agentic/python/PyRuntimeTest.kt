package com.niki914.nexus.agentic.chat.agentic.python

import android.os.IBinder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule

/**
 * 用 runBlocking（真实时间）而不是 runTest（虚拟时钟）：
 * 超时路径依赖 withTimeout 对真实阻塞线程的取消，虚拟时钟下时序不可控。
 */
class PyRuntimeTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private class FakeWorker : IPythonWorkerService {
        override fun asBinder(): IBinder = error("not used in tests")

        var execResult: String? = "ok"
        var execBlockMs: Long = 0L
        var pingBlockMs: Long = 0L
        var pingCalls = 0
        var killed = false
        var killCount = 0
        var execCalls = 0

        override fun exec(code: String?, timeoutMs: Long): String? {
            execCalls++
            if (execBlockMs > 0) Thread.sleep(execBlockMs)
            return execResult
        }

        override fun ping(): String? {
            // 只在第一次 ping 阻塞：重连后的第二次 ping 应恢复正常（模拟新进程）
            val block = if (pingCalls++ == 0) pingBlockMs else 0L
            if (block > 0) Thread.sleep(block)
            return "0"
        }

        override fun kill() {
            killed = true
            killCount++
        }
    }

    @After
    fun tearDown() {
        PyRuntime.resetForTest()
    }

    @Test
    fun `exec normal path returns worker result`() = runBlocking {
        val fake = FakeWorker().also { PyRuntime.testService = it }

        val result = PyRuntime.exec("print('hi')", 30_000L)

        assertEquals("ok", result)
        assertEquals(1, fake.execCalls)
        assertFalse(fake.killed)
    }

    @Test
    fun `exec normal timeout returns TimeoutError text without killing`() = runBlocking {
        val fake = FakeWorker().also { PyRuntime.testService = it }
        fake.execResult = "Execution timed out after 30s\n\nPartial output:\n"

        val result = PyRuntime.exec("import time; time.sleep(999)", 30_000L)

        assertTrue(result.contains("timed out after"))
        assertFalse(fake.killed)
    }

    @Test
    fun `exec hard-stuck interpreter kills worker and rethrows timeout`() = runBlocking {
        val fake = FakeWorker().also {
            PyRuntime.testService = it
            PyRuntime.pingTimeoutMsOverride = 200
        }
        fake.execBlockMs = 10_000L // blocks past the withTimeout wrapper (30ms + 2s grace)

        var caught: Throwable? = null
        try {
            PyRuntime.exec("while True: pass", 30L)
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue(fake.killed)
        assertTrue(caught is TimeoutCancellationException)
    }

    @Test
    fun `stuck ping kills worker then retries exec`() = runBlocking {
        val fake = FakeWorker().also {
            PyRuntime.testService = it
            PyRuntime.pingTimeoutMsOverride = 200
        }
        fake.pingBlockMs = 10_000L // interpreter unresponsive

        val result = PyRuntime.exec("print('hi')", 30L)

        // 测试模式无法真正重连：kill 后复用同一 fake 重试（生产路径是重新 bind 新进程）
        assertTrue(fake.killed)
        assertEquals(1, fake.execCalls)
        assertEquals("ok", result)
    }

    @Test
    fun `kill after reconnect still hard-stops the in-flight exec`() = runBlocking {
        // P1 回归：健康检查失败触发 killAndReconnect 后，重连的在途 exec
        // 必须仍处于终止保护内——终止键不能因 pythonUsed 被重置而放行。
        val fake = FakeWorker().also {
            PyRuntime.testService = it
            PyRuntime.pingTimeoutMsOverride = 200
        }
        fake.pingBlockMs = 10_000L // 首次 ping 卡死 → killAndReconnect（kill #1）

        PyRuntime.exec("print('hi')", 30L) // 重连后 retry 成功

        PyRuntime.kill() // 终止键

        assertEquals(2, fake.killCount) // #1 来自 ping 卡死，#2 来自终止键
    }

    @Test
    fun `kill is no-op when python was never used this warm cycle`() = runBlocking {
        val fake = FakeWorker().also { PyRuntime.testService = it }

        PyRuntime.kill()

        assertFalse(fake.killed)
    }

    @Test
    fun `kill after exec hard-stops the worker`() = runBlocking {
        val fake = FakeWorker().also { PyRuntime.testService = it }
        PyRuntime.exec("print('hi')", 30_000L)
        assertFalse(fake.killed)

        PyRuntime.kill()

        assertTrue(fake.killed)
    }
}
