package com.niki914.zafiro.accessibility.port

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.zafiro.accessibility.driver.AccessibilityDriver
import com.niki914.zafiro.accessibility.shell.ShellPointerDriver
import com.niki914.zafiro.api.screen.ActionStatus
import com.niki914.zafiro.api.screen.PointerAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：手势或全局按键失败时，shell 的结果必须交回；上一级成功时不调用 shell。
class A11yDisplayPortTest {

    @Test
    fun gestureSuccessDoesNotCallShell() = runBlocking {
        var shellCalls = 0
        val port = port(gestureResult = true) {
            shellCalls += 1
            true
        }

        val status = port.inject(PointerAction.Tap(1, 2))

        assertTrue(status is ActionStatus.Done)
        assertEquals(0, shellCalls)
    }

    @Test
    fun gestureAndShellFailureIsReported() = runBlocking {
        var shellCalls = 0
        val port = port(gestureResult = false) {
            shellCalls += 1
            false
        }

        val status = port.inject(PointerAction.Tap(1, 2))

        assertTrue(status is ActionStatus.Failed)
        assertEquals("ACTION_FAILED", (status as ActionStatus.Failed).code)
        assertEquals(1, shellCalls)
    }

    @Test
    fun globalKeySuccessDoesNotCallShell() = runBlocking {
        var shellCalls = 0
        val port = port(globalResult = true) {
            shellCalls += 1
            false
        }

        val status = port.inject(PointerAction.Key(4))

        assertTrue(status is ActionStatus.Done)
        assertEquals(0, shellCalls)
    }

    private fun port(
        gestureResult: Boolean = false,
        globalResult: Boolean = false,
        shell: suspend (String) -> Boolean,
    ): A11yDisplayPort {
        return A11yDisplayPort(
            displayId = 0,
            driver = FakeDriver(gestureResult, globalResult),
            shell = ShellPointerDriver(shell),
        )
    }

    private class FakeDriver(
        private val gestureResult: Boolean,
        private val globalResult: Boolean,
    ) : AccessibilityDriver {
        override val isConnected: Boolean = true

        override fun getActiveWindowRoot(displayId: Int): AccessibilityNodeInfo? = null

        override suspend fun dispatchGesture(
            displayId: Int,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            duration: Long,
        ): Boolean = gestureResult

        override suspend fun takeScreenshot(displayId: Int): Bitmap? = null

        override fun performNodeAction(
            node: AccessibilityNodeInfo,
            action: Int,
            text: String?,
        ): Boolean = false

        override fun getDisplayMetrics(displayId: Int): Pair<Int, Int> = 100 to 100

        override fun performGlobalAction(action: Int): Boolean = globalResult
    }
}
