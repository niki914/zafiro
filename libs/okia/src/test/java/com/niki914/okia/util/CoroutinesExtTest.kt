// githooks:ignore-file bare-catch 单测：在此断言异常抛出与捕获
package com.niki914.okia.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        try {
            runBlocking {
                runCatchingCancellable {
                    withTimeout(1) {
                        delay(50)
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
        val result = runCatchingCancellable { "okia" }
        assertEquals(Result.success("okia"), result)
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
            throw IllegalStateException("something wrong")
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
    fun tryIgnore_swallowsNormalException() {
        var executed = false
        tryIgnore {
            executed = true
            throw IllegalStateException("ignored")
        }
        assertTrue(executed)
    }

    @Test
    fun mapCatchingCancellable_transformsSuccessValue() {
        val original = Result.success(10)
        val transformed = original.mapCatchingCancellable { it * 2 }
        assertEquals(Result.success(20), transformed)
    }

    @Test
    fun mapCatchingCancellable_rethrowsCancellationException() {
        val original = Result.success("ok")
        try {
            original.mapCatchingCancellable {
                throw CancellationException("cancelled inside map")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(e is CancellationException)
        }
    }
}
