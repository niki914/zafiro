package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
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

    private fun createViewModel(
        isOverlayPermissionGranted: suspend () -> Boolean = { true }
    ): GeneralSettingsViewModel {
        return GeneralSettingsViewModel(isOverlayPermissionGranted)
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

        val viewModel = createViewModel()
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
        val viewModel = createViewModel()

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
        val viewModel = createViewModel()
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
        val viewModel = createViewModel(isOverlayPermissionGranted = { true })

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
    fun toggleFloatingBall_whenPermissionMissing_requestsPermissionAndHandlesResult() = runTest {
        val viewModel = createViewModel(isOverlayPermissionGranted = { false })
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(true))
        advanceUntilIdle()

        assertEquals(listOf(GeneralSettingsEffect.RequestOverlayPermission), effects)

        // Denied case
        viewModel.sendIntent(GeneralSettingsIntent.OnOverlayPermissionResult(granted = false))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.floatingBallEnabled)

        // Granted case
        viewModel.sendIntent(GeneralSettingsIntent.OnOverlayPermissionResult(granted = true))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.floatingBallEnabled)
        assertTrue(XRepo.floatingBallEnabled())
    }

    @Test
    fun toggleResidentNotification_updatesStateAndRepo() = runTest {
        val viewModel = createViewModel()

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
    fun selectTimeoutsAndAttempts_updatesStateAndRepo() = runTest {
        val viewModel = createViewModel()

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
}
