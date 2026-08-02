package com.niki914.nexus.agentic.chat.agentic.python

import android.os.IBinder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 runBlocking（真实时间）而不是 runTest（虚拟时钟）：
 * 超时路径依赖 withTimeout 对真实阻塞线程的取消，虚拟时钟下时序不可控。
 */
class PyRuntimeTest {

    private class FakeWorker : IPythonWorkerService {
        override fun asBinder(): IBinder = error("not used in tests")

        var execResult: String? = "ok"
        var execBlockMs: Long = 0L
        var pingBlockMs: Long = 0L
        var killed = false
        var execCalls = 0

        override fun exec(code: String?, timeoutMs: Long): String? {
            execCalls++
            if (execBlockMs > 0) Thread.sleep(execBlockMs)
            return execResult
        }

        override fun ping(): String? {
            if (pingBlockMs > 0) Thread.sleep(pingBlockMs)
            return "0"
        }

        override fun kill() {
            killed = true
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
        val fake = FakeWorker().also { PyRuntime.testService = it }
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
        val fake = FakeWorker().also { PyRuntime.testService = it }
        fake.pingBlockMs = 10_000L // interpreter unresponsive

        val result = PyRuntime.exec("print('hi')", 30L)

        // 测试模式无法真正重连：kill 后复用同一 fake 重试（生产路径是重新 bind 新进程）
        assertTrue(fake.killed)
        assertEquals(1, fake.execCalls)
        assertEquals("ok", result)
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
