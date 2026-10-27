// 保护：高频输入下的任务合并与延时触发，防止未防抖连击导致重复请求或在途任务丢失
package com.niki914.zafiro.api.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun executesActionOnlyAfterDelayWindowPasses() = runTest {
        val debouncer = debouncer(defaultDelayMs = 200L)
        var executionCount = 0

        debouncer.debounce { executionCount++ }

        advanceTimeBy(100L)
        runCurrent()
        assertEquals(0, executionCount)
        assertTrue(debouncer.isPending)

        advanceTimeBy(101L)
        runCurrent()
        assertEquals(1, executionCount)
        assertFalse(debouncer.isPending)
    }

    @Test
    fun dropsPreviousActionWhenNewOneTriggeredWithinDelayWindow() = runTest {
        val debouncer = debouncer(defaultDelayMs = 200L)
        val executions = mutableListOf<String>()

        debouncer.debounce { executions.add("first") }
        advanceTimeBy(100L)
        runCurrent()

        debouncer.debounce { executions.add("second") }
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(emptyList<String>(), executions)

        advanceTimeBy(60L)
        runCurrent()
        assertEquals(listOf("second"), executions)
    }

    @Test
    fun cancelsPendingActionWithoutExecution() = runTest {
        val debouncer = debouncer(defaultDelayMs = 200L)
        var executed = false

        debouncer.debounce { executed = true }
        assertTrue(debouncer.isPending)

        debouncer.cancel()
        assertFalse(debouncer.isPending)

        advanceTimeBy(300L)
        runCurrent()
        assertFalse(executed)
    }

    @Test
    fun flushExecutesPendingActionImmediately() = runTest {
        val debouncer = debouncer(defaultDelayMs = 300L)
        var executed = false

        debouncer.debounce { executed = true }
        assertEquals(false, executed)

        debouncer.flush()
        runCurrent()
        assertTrue(executed)
        assertFalse(debouncer.isPending)
    }

    @Test
    fun flushDoesNothingWhenNoActionIsPending() = runTest {
        val debouncer = debouncer(defaultDelayMs = 300L)
        var executed = false

        debouncer.flush()
        runCurrent()
        assertFalse(executed)
    }
}
