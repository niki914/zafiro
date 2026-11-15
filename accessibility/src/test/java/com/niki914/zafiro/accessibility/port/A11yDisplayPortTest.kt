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

// 保护：手势与主副屏按键路由；副屏拦截全局逃逸按键；上一级成功时不调用 shell。
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

    @Test
    fun rejectsGlobalSystemKeyOnSecondaryDisplay() = runBlocking {
        var shellCalls = 0
        val port = port(displayId = 39) {
            shellCalls += 1
            true
        }

        val status = port.inject(PointerAction.Key(3)) // KEYCODE_HOME

        assertTrue(status is ActionStatus.Failed)
        assertEquals("UNSUPPORTED_GLOBAL_KEY_ON_VIRTUAL_SCREEN", (status as ActionStatus.Failed).code)
        assertEquals(0, shellCalls)
    }

    @Test
    fun allowsBackKeyOnSecondaryDisplay() = runBlocking {
        var lastCmd = ""
        val port = port(displayId = 39) { cmd ->
            lastCmd = cmd
            true
        }

        val status = port.inject(PointerAction.Key(4)) // KEYCODE_BACK

        assertTrue(status is ActionStatus.Done)
        assertEquals("input -d 39 keyevent 4", lastCmd)
    }

    private fun port(
        displayId: Int = 0,
        gestureResult: Boolean = false,
        globalResult: Boolean = false,
        shell: suspend (String) -> Boolean,
    ): A11yDisplayPort {
        return A11yDisplayPort(
            displayId = displayId,
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
