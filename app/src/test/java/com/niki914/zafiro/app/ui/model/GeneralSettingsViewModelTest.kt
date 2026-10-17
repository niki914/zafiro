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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 保护：通用设置的加载与开关落盘；权限未授时开关不亮、不写仓。
@OptIn(ExperimentalCoroutinesApi::class)
class GeneralSettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = Files.createTempDirectory("test_files").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
    }

    /** 权限结果由注册表里的假实现控制：ViewModel 自己经 ServiceRegistry 取 PermissionManager。 */
    private fun installPermission(state: PermissionState) {
        installService<PermissionManager>(FakePermissionManager(state))
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
        XRepo.setLoadLastConversationOnStartup(false)
        XRepo.setAlwaysShowMessageActions(false)
        XRepo.setVoiceAssistantIsolatedSession(false)
        XRepo.setFloatingBallEnabled(true)
        XRepo.setResidentNotificationEnabled(true)

        val viewModel = GeneralSettingsViewModel()
        viewModel.sendIntent(GeneralSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.loadLastConversation)
        assertFalse(state.alwaysShowMessageActions)
        assertFalse(state.voiceAssistantIsolatedSession)
        assertTrue(state.floatingBallEnabled)
        assertTrue(state.residentNotificationEnabled)
        assertFalse(state.isLoading)
    }

    @Test
    fun toggleFloatingBall_whenPermissionGranted_updatesStateAndRepo() = runTest {
        installPermission(PermissionState.GRANTED)
        val viewModel = GeneralSettingsViewModel()

        assertFalse(viewModel.uiStateFlow.value.floatingBallEnabled)
        assertFalse(XRepo.floatingBallEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(true))
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.floatingBallEnabled)
        assertTrue(XRepo.floatingBallEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.floatingBallEnabled)
        assertFalse(XRepo.floatingBallEnabled())
    }

    @Test
    fun toggleFloatingBall_whenPermissionMissing_staysOffAndEmitsNoEffect() = runTest {
        installPermission(PermissionState.DENIED_BY_USER)
        val viewModel = GeneralSettingsViewModel()

        viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(true))
        advanceUntilIdle()

        // 申请在 ViewModel 内完成，UI 层不参与；没授上则开关不亮、不落盘
        assertFalse(viewModel.uiStateFlow.value.floatingBallEnabled)
        assertFalse(XRepo.floatingBallEnabled())
    }

    @Test
    fun toggleResidentNotification_whenPermissionGranted_updatesStateAndRepo() = runTest {
        installPermission(PermissionState.GRANTED)
        val viewModel = GeneralSettingsViewModel()

        assertFalse(viewModel.uiStateFlow.value.residentNotificationEnabled)
        assertFalse(XRepo.residentNotificationEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleResidentNotification(true))
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.residentNotificationEnabled)
        assertTrue(XRepo.residentNotificationEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleResidentNotification(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.residentNotificationEnabled)
        assertFalse(XRepo.residentNotificationEnabled())
    }

    @Test
    fun toggleResidentNotification_whenPermissionMissing_staysOffAndEmitsNoEffect() = runTest {
        installPermission(PermissionState.DENIED_BY_USER)
        val viewModel = GeneralSettingsViewModel()

        viewModel.sendIntent(GeneralSettingsIntent.ToggleResidentNotification(true))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.residentNotificationEnabled)
        assertFalse(XRepo.residentNotificationEnabled())
    }

    @Test
    fun toggleTurnNotification_whenPermissionGranted_updatesStateAndRepo() = runTest {
        installPermission(PermissionState.GRANTED)
        val viewModel = GeneralSettingsViewModel()

        assertFalse(viewModel.uiStateFlow.value.turnNotificationEnabled)
        assertFalse(XRepo.turnNotificationEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleTurnNotification(true))
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.turnNotificationEnabled)
        assertTrue(XRepo.turnNotificationEnabled())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleTurnNotification(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.turnNotificationEnabled)
        assertFalse(XRepo.turnNotificationEnabled())
    }

    @Test
    fun toggleTurnNotification_whenPermissionMissing_staysOff() = runTest {
        installPermission(PermissionState.DENIED_BY_USER)
        val viewModel = GeneralSettingsViewModel()

        viewModel.sendIntent(GeneralSettingsIntent.ToggleTurnNotification(true))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.turnNotificationEnabled)
        assertFalse(XRepo.turnNotificationEnabled())
    }

    @Test
    fun toggleVoiceAssistantIsolatedSession_updatesStateAndRepo() = runTest {
        val viewModel = GeneralSettingsViewModel()

        assertTrue(viewModel.uiStateFlow.value.voiceAssistantIsolatedSession)
        assertTrue(XRepo.voiceAssistantIsolatedSession())

        viewModel.sendIntent(GeneralSettingsIntent.ToggleVoiceAssistantIsolatedSession(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.voiceAssistantIsolatedSession)
        assertFalse(XRepo.voiceAssistantIsolatedSession())
    }

    private class FakePermissionManager(
        private val state: PermissionState,
    ) : PermissionManager {
        override fun status(permission: Permission): PermissionState = state

        override suspend fun request(permission: Permission): PermissionResult =
            PermissionResult(permission, state, emptyList())

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel,
        ): PermissionResult = PermissionResult(permission, state, emptyList())

        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not needed in test")
    }
}
