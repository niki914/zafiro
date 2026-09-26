package com.niki914.zafiro.remoteview.floatingball

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingBallViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun requestExpand_expandsAndUnsubmerges() = runTest {
        val viewModel = FloatingBallViewModel()
        viewModel.sendIntent(FloatingBallIntent.RequestExpand)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(FloatingBallState.Expanded, state.ballState)
        assertFalse(state.isSubmerged)
    }

    @Test
    fun requestCollapse_collapsesAndClosesDetail() = runTest {
        val viewModel = FloatingBallViewModel()
        viewModel.sendIntent(FloatingBallIntent.RequestExpand)
        advanceUntilIdle()
        viewModel.sendIntent(
            FloatingBallIntent.UpdateApprovalRequest(
                ApprovalRequest("sh", "echo 1", "test_rule")
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

        val request = ApprovalRequest("terminal", "rm -rf /tmp", "dangerous_rm")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(FloatingBallState.Expanded, state.ballState)
        assertFalse(state.isSubmerged)
        assertEquals(request, state.approvalRequest)
        assertTrue(state.isApprovalPending)
    }

    @Test
    fun openAndCloseDetail_togglesState() = runTest {
        val viewModel = FloatingBallViewModel()
        val request = ApprovalRequest("terminal", "ls", "safe_ls")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
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
        val request = ApprovalRequest("terminal", "id", "safe_id")
        viewModel.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
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
        val request = ApprovalRequest("terminal", "shutdown", "danger")
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
}
