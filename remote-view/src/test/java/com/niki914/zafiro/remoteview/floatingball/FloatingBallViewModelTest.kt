package com.niki914.zafiro.remoteview.floatingball

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import com.niki914.xsettings.XSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingBallViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 假实现，默认自动展开开启；测试直接改 flow 即可切开关。 */
    private class FakeXSettings(
        override val floatingBallAutoExpand: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) : XSettings

    private val settings = FakeXSettings()

    @Before
    fun setUp() {
        installService<XSettings>(settings)
    }

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun initialState_isDefaultCollapsed() = runTest {
        val viewModel = FloatingBallViewModel()
        val state = viewModel.uiStateFlow.value

        assertEquals(FloatingBallState.Collapsed, state.ballState)
        assertFalse(state.isDetailOpen)
        assertTrue(state.isSubmerged)
        assertEquals(DockSide.Right, state.dockSide)
        assertNull(state.preview)
        assertNull(state.approvalRequest)
        assertFalse(state.isStopEnabled)
    }

    @Test
    fun requestExpand_emitsExpandCardEffect() = runTest {
        val viewModel = FloatingBallViewModel()
        val effectDeferred = async { viewModel.uiEffect.first() }
        viewModel.sendIntent(FloatingBallIntent.RequestExpand)
        advanceUntilIdle()

        assertEquals(FloatingBallEffect.ExpandCard, effectDeferred.await())
    }

    @Test
    fun commitExpand_expandsAndUnsubmerges() = runTest {
        val viewModel = FloatingBallViewModel()
        viewModel.sendIntent(FloatingBallIntent.CommitExpand)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(FloatingBallState.Expanded, state.ballState)
        assertFalse(state.isSubmerged)
    }

    @Test
    fun requestCollapse_collapsesAndClosesDetail() = runTest {
        val viewModel = FloatingBallViewModel()
        viewModel.sendIntent(FloatingBallIntent.CommitExpand)
        advanceUntilIdle()
        viewModel.sendIntent(
            FloatingBallIntent.UpdateApprovalRequest(
                ApprovalRequest.ToolExecution("sh", "echo 1", "test_rule")
            )
        )
        advanceUntilIdle()
        viewModel.sendIntent(FloatingBallIntent.OpenDetail)
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.isDetailOpen)

        viewModel.sendIntent(FloatingBallIntent.RequestCollapse(DockSide.Left))
        advanceUntilIdle()
        val state = viewModel.uiStateFlow.value
        assertEquals(FloatingBallState.Collapsed, state.ballState)
        assertFalse(state.isDetailOpen)
    }

    @Test
    fun approvalRequest_autoExpandsIfCollapsed() = runTest {
        val viewModel = FloatingBallViewModel()
        assertEquals(FloatingBallState.Collapsed, viewModel.uiStateFlow.value.ballState)

        val effectDeferred = async { viewModel.uiEffect.first() }
        val request = ApprovalRequest.ToolExecution("terminal", "rm -rf /tmp", "dangerous_rm")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
        advanceUntilIdle()

        assertEquals(request, viewModel.uiStateFlow.value.approvalRequest)
        assertTrue(viewModel.uiStateFlow.value.isApprovalPending)
        assertEquals(FloatingBallEffect.ExpandCard, effectDeferred.await())

        viewModel.sendIntent(FloatingBallIntent.CommitExpand)
        advanceUntilIdle()
        val state = viewModel.uiStateFlow.value
        assertEquals(FloatingBallState.Expanded, state.ballState)
        assertFalse(state.isSubmerged)
    }

    @Test
    fun openAndCloseDetail_togglesState() = runTest {
        val viewModel = FloatingBallViewModel()
        val request = ApprovalRequest.ToolExecution("terminal", "ls", "safe_ls")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
        viewModel.sendIntent(FloatingBallIntent.CommitExpand)
        advanceUntilIdle()

        viewModel.sendIntent(FloatingBallIntent.OpenDetail)
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.isDetailOpen)

        viewModel.sendIntent(FloatingBallIntent.CloseDetail)
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.isDetailOpen)
    }

    @Test
    fun allowApproval_settlesAndClearsRequest() = runTest {
        val viewModel = FloatingBallViewModel()
        val request = ApprovalRequest.ToolExecution("terminal", "id", "safe_id")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
        viewModel.sendIntent(FloatingBallIntent.CommitExpand)
        advanceUntilIdle()
        viewModel.sendIntent(FloatingBallIntent.OpenDetail)
        advanceUntilIdle()

        val effectDeferred = async { viewModel.uiEffect.first() }
        viewModel.sendIntent(FloatingBallIntent.AllowApproval)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isDetailOpen)
        assertNull(state.approvalRequest)
        assertEquals(FloatingBallEffect.SettleApproval(ApprovalDecision.Allow), effectDeferred.await())
    }

    @Test
    fun denyApproval_settlesAndClearsRequest() = runTest {
        val viewModel = FloatingBallViewModel()
        val request = ApprovalRequest.ToolExecution("terminal", "shutdown", "danger")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
        advanceUntilIdle()

        val effectDeferred = async { viewModel.uiEffect.first() }
        viewModel.sendIntent(FloatingBallIntent.DenyApproval)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isDetailOpen)
        assertNull(state.approvalRequest)
        assertEquals(FloatingBallEffect.SettleApproval(ApprovalDecision.Deny), effectDeferred.await())
    }

    @Test
    fun updateAgentStatus_updatesPreviewAndRunning() = runTest {
        val viewModel = FloatingBallViewModel()
        viewModel.sendIntent(FloatingBallIntent.UpdateAgentStatus("正在生成天气信息...", true))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals("正在生成天气信息...", state.preview)
        assertTrue(state.isStopEnabled)
    }

    @Test
    fun outcomeArrived_autoExpandsOnlyWhenSettingEnabled() = runTest {
        val viewModel = FloatingBallViewModel()
        assertEquals(FloatingBallState.Collapsed, viewModel.uiStateFlow.value.ballState)

        // 先进入运行中，再拿到结局，才算是「结局到达」
        viewModel.sendIntent(FloatingBallIntent.UpdateAgentStatus("跑", true))
        advanceUntilIdle()

        val effectDeferred = async { viewModel.uiEffect.first() }
        viewModel.sendIntent(
            FloatingBallIntent.UpdateAgentStatus("完事", isRunning = false, lastOutcome = TurnOutcome.Completed)
        )
        advanceUntilIdle()
        assertEquals(FloatingBallEffect.ExpandCard, effectDeferred.await())
    }

    @Test
    fun outcomeArrived_doesNotExpandWhenAutoExpandDisabled() = runTest {
        settings.floatingBallAutoExpand.value = false
        val viewModel = FloatingBallViewModel()

        viewModel.sendIntent(FloatingBallIntent.UpdateAgentStatus("跑", true))
        advanceUntilIdle()
        viewModel.sendIntent(
            FloatingBallIntent.UpdateAgentStatus("完事", isRunning = false, lastOutcome = TurnOutcome.Failed)
        )
        advanceUntilIdle()

        // 状态照旧更新（小球靠颜色提示），但不展开
        assertEquals(TurnOutcome.Failed, viewModel.uiStateFlow.value.lastOutcome)
        assertEquals(FloatingBallState.Collapsed, viewModel.uiStateFlow.value.ballState)
    }

    @Test
    fun approvalArrived_doesNotExpandWhenAutoExpandDisabled() = runTest {
        settings.floatingBallAutoExpand.value = false
        val viewModel = FloatingBallViewModel()

        viewModel.sendIntent(
            FloatingBallIntent.UpdateApprovalRequest(
                ApprovalRequest.ToolExecution("terminal", "rm -rf /", "danger")
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.isApprovalPending)
        assertEquals(FloatingBallState.Collapsed, viewModel.uiStateFlow.value.ballState)
    }
}
