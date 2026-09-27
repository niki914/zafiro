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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class GeneralSettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = java.nio.file.Files.createTempDirectory("test_files").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): java.io.File = tempDir
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
        XRepo.setLanguageTag("zh-CN")
        XRepo.setLoadLastConversationOnStartup(true)
        XRepo.setAlwaysShowMessageActions(false)
        XRepo.setLlmIdleTimeoutSeconds(90L)
        XRepo.setLlmRetryMaxAttempts(5)
        XRepo.setKeepScreenOn(false)
        XRepo.setFloatingBallEnabled(true)
        XRepo.setResidentNotificationEnabled(true)

        val viewModel = GeneralSettingsViewModel()
        viewModel.sendIntent(GeneralSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals("zh-CN", state.languageTag)
        assertTrue(state.loadLastConversation)
        assertFalse(state.alwaysShowMessageActions)
        assertEquals(90L, state.idleTimeoutSeconds)
        assertEquals(5, state.retryMaxAttempts)
        assertFalse(state.keepScreenOn)
        assertTrue(state.floatingBallEnabled)
        assertTrue(state.residentNotificationEnabled)
        assertFalse(state.isLoading)
        assertNull(state.activeDialog)
    }

    @Test
    fun dialogState_openAndDismissWorkAsExpected() = runTest {
        val viewModel = GeneralSettingsViewModel()

        viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.Language))
        advanceUntilIdle()
        assertEquals(GeneralSettingsDialog.Language, viewModel.uiStateFlow.value.activeDialog)

        viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.IdleTimeout))
        advanceUntilIdle()
        assertEquals(GeneralSettingsDialog.IdleTimeout, viewModel.uiStateFlow.value.activeDialog)

        viewModel.sendIntent(GeneralSettingsIntent.DismissDialog)
        advanceUntilIdle()
        assertNull(viewModel.uiStateFlow.value.activeDialog)
    }

    @Test
    fun selectLanguage_updatesStateRepoAndEmitsEffect() = runTest {
        val viewModel = GeneralSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.Language))
        viewModel.sendIntent(GeneralSettingsIntent.SelectLanguage("en"))
        advanceUntilIdle()

        assertEquals("en", viewModel.uiStateFlow.value.languageTag)
        assertNull(viewModel.uiStateFlow.value.activeDialog)
        assertEquals("en", XRepo.languageTag())
        assertEquals(
            listOf(GeneralSettingsEffect.ApplyApplicationLocales("en")),
            effects,
        )
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
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(true))
        advanceUntilIdle()

        // 申请在 ViewModel 内完成，UI 层不再参与，所以既没有 effect，也不会亮开关
        assertTrue(effects.isEmpty())
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
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(GeneralSettingsIntent.ToggleResidentNotification(true))
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertFalse(viewModel.uiStateFlow.value.residentNotificationEnabled)
        assertFalse(XRepo.residentNotificationEnabled())
    }

    @Test
    fun selectTimeoutsAndAttempts_updatesStateAndRepo() = runTest {
        val viewModel = GeneralSettingsViewModel()

        viewModel.sendIntent(GeneralSettingsIntent.SelectIdleTimeout(120L))
        advanceUntilIdle()
        assertEquals(120L, viewModel.uiStateFlow.value.idleTimeoutSeconds)
        assertEquals(120L, XRepo.llmIdleTimeoutSeconds())

        viewModel.sendIntent(GeneralSettingsIntent.SelectRetryMaxAttempts(2))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiStateFlow.value.retryMaxAttempts)
        assertEquals(2, XRepo.llmRetryMaxAttempts())
    }

    private fun TestScope.collectEffects(
        viewModel: GeneralSettingsViewModel,
        count: Int,
    ): MutableList<GeneralSettingsEffect> {
        val effects = mutableListOf<GeneralSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
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
