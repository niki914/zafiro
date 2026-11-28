package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.business.permission.Channel
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionResult
import com.niki914.zafiro.business.permission.PermissionScope
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import com.niki914.zafiro.settings.model.RepetitionSensitivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File
import java.nio.file.Files

// 保护：运行控制页的状态计算与落盘，以及打开对话不休眠时申请电池豁免、拒绝仍可打开、关闭不申请。
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeControlViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = Files.createTempDirectory("test_files").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
    }

    @Before
    fun setUp() {
        ContextProvider.provide(context)
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(context)
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
        ServiceRegistry.clearForTest()
    }

    @Test
    fun load_populatesSettingsFromRepo() = runTest {
        XRepo.setLlmIdleTimeoutSeconds(90L)
        XRepo.setLlmRetryMaxAttempts(5)
        XRepo.setKeepScreenOn(false)
        XRepo.setStopRoundOnCommandDeny(true)
        XRepo.setRepetitionSensitivity(RepetitionSensitivity.Strictest)

        val viewModel = RuntimeControlViewModel()
        viewModel.sendIntent(RuntimeControlIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(90L, state.idleTimeoutSeconds)
        assertEquals(5, state.retryMaxAttempts)
        assertFalse(state.keepScreenOn)
        assertTrue(state.stopRoundOnCommandDeny)
        assertEquals(RepetitionSensitivity.Strictest, state.repetitionSensitivity)
        assertFalse(state.isLoading)
        assertNull(state.activeDialog)
    }

    @Test
    fun selectSettings_updatesStateAndRepo() = runTest {
        val viewModel = RuntimeControlViewModel()

        viewModel.sendIntent(RuntimeControlIntent.SelectIdleTimeout(120L))
        advanceUntilIdle()
        assertEquals(120L, viewModel.uiStateFlow.value.idleTimeoutSeconds)
        assertEquals(120L, XRepo.llmIdleTimeoutSeconds())

        viewModel.sendIntent(RuntimeControlIntent.SelectRetryMaxAttempts(2))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiStateFlow.value.retryMaxAttempts)
        assertEquals(2, XRepo.llmRetryMaxAttempts())
    }

    @Test
    fun selectRepetitionSensitivity_updatesStateAndClosesDialogAndPersists() = runTest {
        val viewModel = RuntimeControlViewModel()
        assertEquals(RepetitionSensitivity.DEFAULT, viewModel.uiStateFlow.value.repetitionSensitivity)

        viewModel.sendIntent(
            RuntimeControlIntent.OpenDialog(RuntimeControlDialog.RepetitionSensitivity)
        )
        advanceUntilIdle()
        assertEquals(
            RuntimeControlDialog.RepetitionSensitivity,
            viewModel.uiStateFlow.value.activeDialog,
        )

        viewModel.sendIntent(
            RuntimeControlIntent.SelectRepetitionSensitivity(RepetitionSensitivity.Off)
        )
        advanceUntilIdle()

        assertEquals(RepetitionSensitivity.Off, viewModel.uiStateFlow.value.repetitionSensitivity)
        assertNull(viewModel.uiStateFlow.value.activeDialog)
        assertEquals(RepetitionSensitivity.Off, XRepo.repetitionSensitivity())
    }

    @Test
    fun toggleKeepScreenOn_updatesStateAndRepo() = runTest {
        val viewModel = RuntimeControlViewModel()

        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
        viewModel.sendIntent(RuntimeControlIntent.ToggleKeepScreenOn(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.keepScreenOn)
        assertFalse(XRepo.keepScreenOn())
    }

    @Test
    fun toggleKeepScreenOn_onRequestsExemptionAndStillEnablesWhenDenied() = runTest {
        val permissions = RecordingPermissionManager(
            state = PermissionState.DENIED_BY_USER,
            requestResult = PermissionState.DENIED_BY_USER,
        )
        installService<PermissionManager>(permissions)
        XRepo.setKeepScreenOn(false)

        val viewModel = RuntimeControlViewModel()
        viewModel.sendIntent(RuntimeControlIntent.Load)
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.keepScreenOn)
        assertTrue(permissions.requested.isEmpty())

        viewModel.sendIntent(RuntimeControlIntent.ToggleKeepScreenOn(true))
        advanceUntilIdle()

        assertEquals(listOf(Permission.BATTERY_OPTIMIZATION), permissions.requested)
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
        assertTrue(XRepo.keepScreenOn())
    }

    @Test
    fun toggleKeepScreenOn_offDoesNotRequestExemption() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.DENIED_BY_USER)
        installService<PermissionManager>(permissions)

        val viewModel = RuntimeControlViewModel()
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
        viewModel.sendIntent(RuntimeControlIntent.ToggleKeepScreenOn(false))
        advanceUntilIdle()

        assertTrue(permissions.requested.isEmpty())
        assertFalse(viewModel.uiStateFlow.value.keepScreenOn)
        assertFalse(XRepo.keepScreenOn())
    }

    @Test
    fun load_whenKeepScreenOnRequestsExemptionIfMissing() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.DENIED_BY_USER)
        installService<PermissionManager>(permissions)
        XRepo.setKeepScreenOn(true)

        val viewModel = RuntimeControlViewModel()
        viewModel.sendIntent(RuntimeControlIntent.Load)
        advanceUntilIdle()

        assertEquals(listOf(Permission.BATTERY_OPTIMIZATION), permissions.requested)
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
    }

    @Test
    fun load_whenKeepScreenOnAlreadyGrantedDoesNotRequest() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.GRANTED)
        installService<PermissionManager>(permissions)
        XRepo.setKeepScreenOn(true)

        val viewModel = RuntimeControlViewModel()
        viewModel.sendIntent(RuntimeControlIntent.Load)
        advanceUntilIdle()

        assertTrue(permissions.requested.isEmpty())
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
    }

    @Test
    fun toggleStopRoundOnCommandDeny_updatesStateAndRepo() = runTest {
        val viewModel = RuntimeControlViewModel()

        assertTrue(viewModel.uiStateFlow.value.stopRoundOnCommandDeny)
        viewModel.sendIntent(RuntimeControlIntent.ToggleStopRoundOnCommandDeny(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.stopRoundOnCommandDeny)
        assertFalse(XRepo.stopRoundOnCommandDeny())
    }

    @Test
    fun selectCompactionThreshold_updatesStateAndClosesDialogAndPersists() = runTest {
        val viewModel = RuntimeControlViewModel()
        assertEquals(70, viewModel.uiStateFlow.value.compactionThresholdPercent)

        viewModel.sendIntent(
            RuntimeControlIntent.OpenDialog(RuntimeControlDialog.CompactionThreshold)
        )
        advanceUntilIdle()
        assertEquals(
            RuntimeControlDialog.CompactionThreshold,
            viewModel.uiStateFlow.value.activeDialog,
        )

        viewModel.sendIntent(
            RuntimeControlIntent.SelectCompactionThreshold(80)
        )
        advanceUntilIdle()

        assertEquals(80, viewModel.uiStateFlow.value.compactionThresholdPercent)
        assertNull(viewModel.uiStateFlow.value.activeDialog)
        assertEquals(80, XRepo.contextCompactionThresholdPercent())
    }

    private class RecordingPermissionManager(
        private val state: PermissionState,
        private val requestResult: PermissionState = state,
    ) : PermissionManager {
        val requested = mutableListOf<Permission>()

        override fun status(permission: Permission): PermissionState = state

        override suspend fun request(permission: Permission): PermissionResult {
            requested += permission
            return PermissionResult(permission, requestResult, emptyList())
        }

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel,
        ): PermissionResult = request(permission)

        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not needed in test")
    }
}
