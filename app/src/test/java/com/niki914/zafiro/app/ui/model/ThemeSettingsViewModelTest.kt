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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 保护：选择语言后落盘并广播 locales 应用 effect（漏掉任一步 = 选了不生效）。
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {

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
    fun selectLanguage_updatesStateRepoClosesDialogAndEmitsEffect() = runTest {
        val viewModel = ThemeSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(ThemeSettingsIntent.OpenLanguageDialog)
        viewModel.sendIntent(ThemeSettingsIntent.SelectLanguage("en"))
        advanceUntilIdle()

        assertEquals("en", viewModel.uiStateFlow.value.languageTag)
        assertFalse(viewModel.uiStateFlow.value.languageDialogVisible)
        assertEquals("en", XRepo.languageTag())
        assertEquals(
            listOf(ThemeSettingsEffect.ApplyApplicationLocales("en")),
            effects,
        )
    }

    private fun TestScope.collectEffects(
        viewModel: ThemeSettingsViewModel,
        count: Int,
    ): MutableList<ThemeSettingsEffect> {
        val effects = mutableListOf<ThemeSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
    }
}
