// 保护：协程取消与线程中断信号不被通用异常捕获工具吞掉，保证结构化并发正常传播
// githooks:ignore-file bare-catch 单测：在此断言异常抛出与捕获
package com.niki914.zafiro.api.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CoroutinesExtTest {

    @Test
    fun runCatchingCancellable_rethrowsCancellationException() {
        val expected = CancellationException("coroutine cancelled")
        try {
            runCatchingCancellable {
                throw expected
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertEquals(expected, e)
        }
    }

    @Test
    fun runCatchingCancellable_rethrowsTimeoutCancellationException() {
        // TimeoutCancellationException 继承自 CancellationException，必须同样逃逸供外部 withTimeout 感知
        try {
            kotlinx.coroutines.runBlocking {
                runCatchingCancellable {
                    kotlinx.coroutines.withTimeout(1) {
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
            fail("Expected TimeoutCancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is TimeoutCancellationException)
        }
    }

    @Test
    fun runCatchingCancellable_rethrowsInterruptedExceptionAndRestoresInterruptStatus() {
        Thread.interrupted() // 清空可能残留的中断状态
        try {
            runCatchingCancellable {
                throw InterruptedException("thread interrupted")
            }
            fail("Expected InterruptedException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // 测试收尾清理中断状态
        }
    }

    @Test
    fun runCatchingCancellable_catchesNormalExceptionAsFailure() {
        val exception = IOException("network failed")
        val result = runCatchingCancellable {
            throw exception
        }
        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun runCatchingCancellable_returnsSuccessOnNormalValue() {
        val result = runCatchingCancellable { "zafiro" }
        assertEquals(Result.success("zafiro"), result)
    }

    @Test
    fun tryOrNull_rethrowsCancellationException() {
        try {
            tryOrNull {
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun tryOrNull_returnsNullOnNormalException() {
        val result = tryOrNull {
            throw IllegalStateException("something wrong")
        }
        assertNull(result)
    }

    @Test
    fun tryOrDefault_rethrowsCancellationException() {
        try {
            tryOrDefault("fallback") {
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun tryOrDefault_returnsDefaultOnNormalException() {
        val result = tryOrDefault("fallback") {
            throw IllegalArgumentException("bad arg")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun tryIgnore_rethrowsCancellationException() {
        try {
            tryIgnore {
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun tryIgnore_suppressesNormalException() {
        var reachedAfter = false
        tryIgnore {
            throw RuntimeException("suppress me")
        }
        reachedAfter = true
        assertTrue(reachedAfter)
    }

    @Test
    fun mapCatchingCancellable_rethrowsCancellationException() {
        val success = Result.success("ok")
        try {
            success.mapCatchingCancellable {
                throw CancellationException("cancelled in map")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is CancellationException)
        }
    }
}
