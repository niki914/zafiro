package com.niki914.zafiro.app.overlay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Group11 / T-29：真实 [ToolPermissionOverlay.show]/[ToolPermissionOverlay.respond]
 * 经 Robolectric 真实窗口 + 真实等待器驱动（不复制 binder 或 deferred）。
 *
 * 覆盖 design §9.11：请求身份与等待器/窗口身份分离、合并等待、完成/取消/显示失败
 * 清理、旧响应与旧窗口 cleanup/UI 回调不得作用于后继窗口、owner 取消行为不变
 * （不发明 cancel-all）。
 *
 * 调度：Main 与测试体共用 [StandardTestDispatcher]，`runCurrent()` 是显式屏障，
 * 用来在“旧协程 finally 尚未运行”与“后继窗口已存在”之间构造确定性时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ToolPermissionOverlayTest {

    @get:Rule
    val mainRule = OverlayMainDispatcherRule(StandardTestDispatcher())

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ToolPermissionOverlay.dismiss()
    }

    @Test
    fun respondCompletesTheActualWaiterAndReleasesTheWindow() = runTest(mainRule.dispatcher) {
        val shown = startShow("req-a")

        assertTrue(ToolPermissionOverlay.respond("req-a", true))
        runCurrent()

        assertEquals(true, shown.await())
        // 完成后绑定解除：旧响应不能再触碰任何请求。
        assertFalse(ToolPermissionOverlay.respond("req-a", true))
    }

    @Test
    fun respondDenialIsDeliveredToTheSameWaiter() = runTest(mainRule.dispatcher) {
        val shown = startShow("req-a")

        assertTrue(ToolPermissionOverlay.respond("req-a", false))
        runCurrent()

        assertEquals(false, shown.await())
    }

    @Test
    fun mergedRequestIdsShareOneWaiterAndEitherIdCompletesBoth() =
        runTest(mainRule.dispatcher) {
            val first = startShow("req-a")
            val second = startShow("req-b")

            // 第二个请求并入同一等待器：用它的身份完成时两个调用者都结束。
            assertTrue(ToolPermissionOverlay.respond("req-b", false))
            runCurrent()

            assertEquals(false, first.await())
            assertEquals(false, second.await())
        }

    @Test
    fun mergedCallerCancelUnbindsOnlyItsIdAndKeepsSharedWaiterOpen() =
        runTest(mainRule.dispatcher) {
            val owner = startShow("req-a")
            val merged = startShow("req-b")

            merged.cancel()
            runCurrent()

            // 合并调用者取消只移除自己的绑定，不关闭共享等待器。
            assertFalse(ToolPermissionOverlay.respond("req-b", true))
            assertTrue(ToolPermissionOverlay.respond("req-a", true))
            runCurrent()
            assertEquals(true, owner.await())
        }

    @Test
    fun unknownRequestIdCannotCompleteCurrentWaiter() = runTest(mainRule.dispatcher) {
        val shown = startShow("req-a")

        assertFalse(ToolPermissionOverlay.respond("req-other", true))

        assertTrue(ToolPermissionOverlay.respond("req-a", true))
        runCurrent()
        assertEquals(true, shown.await())
    }

    @Test
    fun ownerCancellationReleasesWindowWithoutCompletingMergedCaller() =
        runTest(mainRule.dispatcher) {
            val owner = startShow("req-a")
            val merged = startShow("req-b")

            owner.cancel()
            runCurrent()

            // 窗口按捕获身份释放；原行为不变：不发明 cancel-all。
            assertFalse(ToolPermissionOverlay.respond("req-a", true))
            assertFalse(ToolPermissionOverlay.respond("req-b", true))
            assertTrue(merged.isActive)

            merged.cancel()
            runCurrent()
        }

    /**
     * compare-clear 挑战：旧等待器的 cleanup 在后继窗口已存在之后才运行，
     * 旧 cleanup 与旧窗口 UI 回调都不得作用于后继等待器。
     */
    @Test
    fun staleWindowCleanupAndUiCallbackCannotClearSuccessor() = runTest(mainRule.dispatcher) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shadowWm = shadowOf(windowManager) as ShadowWindowManagerImpl

        val old = startShow("req-a")
        val oldView = shadowWm.views.single()

        // 显式释放旧窗口，但旧协程仍挂在等待器上（它的 finally 尚未执行）。
        ToolPermissionOverlay.dismiss()
        assertEquals(0, shadowWm.views.size)

        // 后继窗口出现并成为 current；旧协程的 finally 仍未执行。
        val successor = startShow("req-c")
        assertEquals(1, shadowWm.views.size)

        // 旧窗口的 UI 决策回调（dim 层拒绝）此刻触发：只能完成旧等待器。
        assertTrue(oldView.performClick())
        runCurrent()
        assertEquals(false, old.await())

        // 旧 cleanup 在后继已存在后才运行：compare-clear 面对后继等待器，必须失败。
        assertFalse(ToolPermissionOverlay.respond("req-a", false))
        // 后继仍可响应 → 未被旧 cleanup / 旧 UI 回调清掉。
        assertTrue(ToolPermissionOverlay.respond("req-c", false))
        runCurrent()
        assertEquals(false, successor.await())
        // 后继自身收尾后释放，旧身份仍无法复活。
        assertFalse(ToolPermissionOverlay.respond("req-c", false))
    }

    @Test
    fun dismissReleasesWaiterSoLateResponseIsRejected() = runTest(mainRule.dispatcher) {
        val shown = startShow("req-d")

        ToolPermissionOverlay.dismiss()

        // 显式清理后窗口身份已解除。
        assertFalse(ToolPermissionOverlay.respond("req-d", true))
        shown.cancel()
        runCurrent()
    }

    @Test
    fun windowServiceFailureLeavesNoBoundIdentity() = runTest(mainRule.dispatcher) {
        val failing = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.WINDOW_SERVICE) {
                    throw IllegalStateException("no window service")
                }
                return super.getSystemService(name)
            }
        }

        val result = async {
            runCatching { ToolPermissionOverlay.show(failing, request("req-x")) }
        }
        runCurrent()

        assertTrue(result.await().isFailure)
        assertFalse(ToolPermissionOverlay.respond("req-x", true))
    }

    @Test
    fun addViewFailureReleasesCapturedWaiterIdentity() = runTest(mainRule.dispatcher) {
        val real = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val throwing = object : WindowManager by real {
            override fun addView(view: View, params: ViewGroup.LayoutParams) {
                throw IllegalStateException("addView failed")
            }
        }
        val failing = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) throwing else super.getSystemService(name)
        }

        val result = async {
            runCatching { ToolPermissionOverlay.show(failing, request("req-y")) }
        }
        runCurrent()

        assertTrue(result.await().isFailure)
        assertFalse(ToolPermissionOverlay.respond("req-y", true))
    }

    /** 启动一次 show 并排空当前任务，使窗口/等待器已登记。 */
    private fun TestScope.startShow(id: String): Deferred<Boolean> {
        val job = async { ToolPermissionOverlay.show(context, request(id)) }
        runCurrent()
        return job
    }

    private fun request(id: String) = ToolPermissionRequest(
        id = id,
        toolName = "tool",
        command = "echo hi",
        matchedRuleName = "rule",
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
