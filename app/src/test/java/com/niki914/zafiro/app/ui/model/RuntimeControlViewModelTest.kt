package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
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

// 保护：运行控制页的状态计算与落盘——空闲超时/重试次数/屏幕常亮/复读灵敏度的读写与弹窗状态。
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
    }

    @Test
    fun load_populatesSettingsFromRepo() = runTest {
        XRepo.setLlmIdleTimeoutSeconds(90L)
        XRepo.setLlmRetryMaxAttempts(5)
        XRepo.setKeepScreenOn(false)
        XRepo.setRepetitionSensitivity(RepetitionSensitivity.Strictest)

        val viewModel = RuntimeControlViewModel()
        viewModel.sendIntent(RuntimeControlIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(90L, state.idleTimeoutSeconds)
        assertEquals(5, state.retryMaxAttempts)
        assertFalse(state.keepScreenOn)
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
}
