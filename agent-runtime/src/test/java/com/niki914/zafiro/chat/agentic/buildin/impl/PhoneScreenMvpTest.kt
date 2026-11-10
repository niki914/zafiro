package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.accessibility.IAccessibility
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.chat.agentic.screen.PhoneScreens
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 保护：phone 走现有无障碍/启动/截屏；抓屏失败是失败结果，不是把报错当界面树。
// 服务没装时才会是 Screen service is not installed。虚拟屏、浏览器、关掉 phone、历史和标签在这一层直接拒绝。
class PhoneScreenMvpTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    private val files: File = Files.createTempDirectory("phone-screen-mvp").toFile()
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
    }

    @Before
    fun setUp() {
        ServiceRegistry.clearForTest()
        ContextProvider.clearForTest()
        SharedImageCodec.clearForTest()
        LastPhoneCapture.clear()
        AccessibilityController.clearService()
        ContextProvider.provide(context)
        AccessibilityController.setService(DisconnectedAccessibility)
        installService<Screens>(PhoneScreens)
    }

    @After
    fun tearDown() {
        AccessibilityController.clearService()
        LastPhoneCapture.clear()
        SharedImageCodec.clearForTest()
        ServiceRegistry.clearForTest()
        ContextProvider.clearForTest()
    }

    @Test
    fun phoneStaysTheOnlyScreen() = runTest {
        withTimeout(2_000) {
            val listed = text("screens", """{"operation":"list"}""")
            val virtual = text("screens", """{"operation":"open","kind":"virtual_display"}""")
            val browser = text("screens", """{"operation":"open","kind":"browser"}""")
            val closed = text("screens", """{"operation":"close","screen":"phone"}""")
            val again = text("screens", """{"operation":"list"}""")

            assertTrue(listed.payload.startsWith("phone\tPhone\t"))
            assertEquals(SCREEN_UNSUPPORTED, virtual.code)
            assertEquals(SCREEN_UNSUPPORTED, browser.code)
            assertEquals(TextToolResult.Status.Failure, closed.status)
            assertFalse(closed.code == SCREEN_SERVICE_UNAVAILABLE)
            assertEquals(listed.payload, again.payload)
        }
    }

    @Test
    fun historyAndTabsAreUnsupported() = runTest {
        withTimeout(2_000) {
            val history = text("screen_operation", """{"screen":"phone","operation":"history_back"}""")
            val tabs = text("screen_operation", """{"screen":"phone","operation":"tabs"}""")

            assertEquals(SCREEN_UNSUPPORTED, history.code)
            assertEquals(SCREEN_UNSUPPORTED, tabs.code)
        }
    }

    @Test
    fun readEntersAccessibilityCapture() = runTest {
        withTimeout(2_000) {
            val result = text("screen_operation", """{"screen":"phone","operation":"read"}""")

            assertEquals(TextToolResult.Status.Failure, result.status)
            assertTrue(result.message.orEmpty().contains("No active window"))
            assertFalse(result.code == SCREEN_SERVICE_UNAVAILABLE)
        }
    }

    @Test
    fun searchReportsCaptureFailure() = runTest {
        withTimeout(2_000) {
            val result = text(
                "screen_operation",
                """{"screen":"phone","operation":"search","keywords":["settings"]}""",
            )

            assertEquals(TextToolResult.Status.Failure, result.status)
            assertTrue(result.message.orEmpty().contains("No active window"))
            assertFalse(result.code == SCREEN_SERVICE_UNAVAILABLE)
        }
    }

    @Test
    fun tokenTapEntersNodeAction() = runTest {
        withTimeout(2_000) {
            val result = text(
                "screen_operation",
                """{"screen":"phone","operation":"tap","token":"abc_2"}""",
            )

            assertEquals("VERSION_MISMATCH", result.code)
            assertTrue(result.payload.contains("does not match current version"))
        }
    }

    @Test
    fun keyEntersExistingKeyInjection() = runTest {
        withTimeout(2_000) {
            val result = text("screen_operation", """{"screen":"phone","operation":"key","code":4}""")

            assertEquals("KEY_EVENT_FAILED", result.code)
            assertTrue(result.payload.contains("BACK action failed"))
        }
    }

    @Test
    fun showUriEntersExistingOpen() = runTest {
        withTimeout(2_000) {
            val result = text("show", """{"screen":"phone","uri":"https://example.com"}""")

            assertEquals("URI_OPEN_FAILED", result.code)
            assertFalse(result.code == SCREEN_SERVICE_UNAVAILABLE)
        }
    }

    @Test
    fun screenshotEntersExistingCapture() = runTest {
        withTimeout(2_000) {
            val tool = BuiltinToolRegistry.default().find("screenshot")
                ?: error("screenshot is not registered")
            val result = tool.invoke(BuiltinToolRequest("screenshot", """{"screen":"phone"}"""))

            assertEquals("SCREENSHOT_FAILED", result.code)
            assertTrue(result.message.contains("Screen capture failed"))
        }
    }

    private suspend fun text(name: String, json: String): TextToolResult {
        val tool = BuiltinToolRegistry.default().find(name) as RawJsonBuiltinTool
        return TextToolResultCodec.decode(tool.invokeRawJson(BuiltinToolRequest(name, json)))
            ?: error("$name result did not decode")
    }

    private object DisconnectedAccessibility : IAccessibility {
        override val windowRoot: AccessibilityNodeInfo? = null
        override fun performAction(node: AccessibilityNodeInfo, action: Int, text: String?): Boolean = false
        override fun dispatchGesture(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            duration: Long,
        ): Boolean = false

        override fun captureScreenImage(listener: (android.graphics.Bitmap?) -> Unit) {
            listener(null)
        }
    }
}
