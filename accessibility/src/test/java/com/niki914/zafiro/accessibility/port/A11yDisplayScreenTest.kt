package com.niki914.zafiro.accessibility.port

import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ActionStatus
import com.niki914.zafiro.api.screen.NodeAction
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.PointerAction
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.SemanticType
import com.niki914.zafiro.api.screen.Target

// 保护：快照版本与节点表一致；过期 token、注入失败不会被当成成功。
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yDisplayScreenTest {

    private class FakeA11yDisplayPort(
        displayId: Int = 0,
    ) : A11yDisplayPort(displayId) {
        var capturedVersion: String? = null
        var performCalled = false
        var injectStatus: ActionStatus = ActionStatus.Done

        override suspend fun inject(action: PointerAction): ActionStatus = injectStatus

        override suspend fun settle(timeoutMs: Long) {
            // Fake port 不需要真实硬件驱动等待稳定
        }

        override suspend fun readNodes(): List<NodeInfo> = readNodes("live")

        override suspend fun readNodes(version: String): List<NodeInfo> {
            capturedVersion = version
            lastYamlSnapshot = "screen: [1080, 2400]\nversion: \"$version\""
            return listOf(
                NodeInfo(
                    index = 0,
                    semanticType = SemanticType.BUTTON,
                    text = "Submit",
                    contentDesc = "Submit button",
                    bounds = Rect(0, 0, 200, 100),
                    isClickable = true,
                    isLongClickable = true,
                    isEditable = true,
                    isScrollable = true,
                    isChecked = false,
                    children = emptyList(),
                )
            )
        }

        override suspend fun perform(token: SemanticToken, action: NodeAction, text: String?): ActionStatus {
            performCalled = true
            return ActionStatus.Done
        }
    }

    @Test
    fun snapshotVersionIsUnifiedAcrossPortTableAndSnapshot() = runBlocking {
        val port = FakeA11yDisplayPort()
        val screen = A11yDisplayScreen(ScreenId("phone"), ScreenKind.Phone, port)

        val snapshot = screen.snapshot()

        // 验证 Issue 1：Snapshot、NodeTable、Port 抓取的版本严格同源
        assertEquals(port.capturedVersion, snapshot.version)
        assertEquals(screen.nodes.version, snapshot.version)
        assertTrue(snapshot.yaml.contains("version: \"${snapshot.version}\""))

        // 验证生成的 Token 可正常在 NodeTable 中反解
        val token = SemanticToken(snapshot.version, 0)
        assertEquals("Submit", screen.nodes.resolve(token)?.text)
    }

    @Test
    fun scrollAndSetTextRejectStaleTokensWithoutTouchingPort() = runBlocking {
        val port = FakeA11yDisplayPort()
        val screen = A11yDisplayScreen(ScreenId("phone"), ScreenKind.Phone, port)
        val snapshot = screen.snapshot()

        val staleToken = SemanticToken("stale_hex_123", 0)

        // 验证 Issue 2：Scroll 遇到过期 Token 必须被 Screen 层拦截，不惊动底层驱动
        port.performCalled = false
        val scrollResult = screen.act(ScreenAction.Scroll(Target.Node(staleToken), forward = true))
        assertTrue(scrollResult is ActResult.Failed)
        assertEquals("NODE_STALE", (scrollResult as ActResult.Failed).code)
        assertFalse(port.performCalled)

        // 验证 Issue 2：SetText 遇到过期 Token 同样被 Screen 层拦截
        port.performCalled = false
        val setTextResult = screen.act(ScreenAction.SetText(Target.Node(staleToken), "input text"))
        assertTrue(setTextResult is ActResult.Failed)
        assertEquals("NODE_STALE", (setTextResult as ActResult.Failed).code)
        assertFalse(port.performCalled)

        // 对比：有效 Token 能够顺利抵达底层 perform
        val validToken = SemanticToken(snapshot.version, 0)
        port.performCalled = false
        val validScroll = screen.act(ScreenAction.Scroll(Target.Node(validToken), forward = true))
        assertTrue(validScroll is ActResult.Done)
        assertTrue(port.performCalled)
    }

    @Test
    fun pointTapFailureStaysFailedAndDoesNotReread() = runBlocking {
        val port = FakeA11yDisplayPort().apply {
            injectStatus = ActionStatus.Failed("ACTION_FAILED")
        }
        val screen = A11yDisplayScreen(ScreenId("phone"), ScreenKind.Phone, port)

        val result = screen.act(ScreenAction.Tap(Target.Point(10, 20)))

        assertTrue(result is ActResult.Failed)
        assertEquals("ACTION_FAILED", (result as ActResult.Failed).code)
        assertEquals(null, port.capturedVersion)
    }

    @Test
    fun pointTapSuccessStillReturnsSnapshot() = runBlocking {
        val port = FakeA11yDisplayPort()
        val screen = A11yDisplayScreen(ScreenId("phone"), ScreenKind.Phone, port)

        val result = screen.act(ScreenAction.Tap(Target.Point(10, 20)))

        assertTrue(result is ActResult.Done)
        assertEquals(port.capturedVersion, (result as ActResult.Done).snapshot.version)
    }
}
