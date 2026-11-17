// 保护：快速提示词表单校验与增删改状态机流转（触发词空格/冒号/重名拦截失效时拦截）
package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.QuickPromptSettingsCodec
import com.niki914.zafiro.repo.RuntimeQuickPrompt
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPromptsSettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun load_readsSavedQuickPrompts() = runTest {
        installStore(quickPromptSettings(samplePrompt()))
        val viewModel = QuickPromptsSettingsViewModel()

        viewModel.sendIntent(QuickPromptsSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals(1, state.items.size)
        assertEquals("btw", state.items.single().name)
        assertEquals("By the way", state.items.single().prompt)
        assertTrue(state.items.single().enabled)
    }

    @Test
    fun save_rejectsWhitespaceSlashesAndSpecialCharacters() = runTest {
        installStore(quickPromptSettings())
        val viewModel = QuickPromptsSettingsViewModel()

        viewModel.sendIntent(QuickPromptsSettingsIntent.StartCreate)
        viewModel.sendIntent(QuickPromptsSettingsIntent.PromptChanged("Some prompt"))

        val invalidNames = listOf(
            "my prompt", // 空格
            "cmd:foo",   // 冒号
            "/btw",      // 前导斜杠
            "foo/bar",   // 内部斜杠
            "foo@bar",   // 特殊符号
            "!bang",     // 标点符号
            "--",        // 纯连字符无字母数字
        )

        for (invalidName in invalidNames) {
            viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged(invalidName))
            viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
            advanceUntilIdle()

            assertEquals(
                "Expected invalid for $invalidName",
                R.string.quick_prompt_error_name_invalid,
                viewModel.uiStateFlow.value.formState.nameErrorResId,
            )
        }
    }

    @Test
    fun save_rejectsDuplicateTriggerKeyCaseInsensitive() = runTest {
        installStore(quickPromptSettings(samplePrompt("btw", "By the way")))
        val viewModel = QuickPromptsSettingsViewModel()
        viewModel.sendIntent(QuickPromptsSettingsIntent.Load)
        advanceUntilIdle()

        viewModel.sendIntent(QuickPromptsSettingsIntent.StartCreate)
        viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged("BTW"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.PromptChanged("Another prompt"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
        advanceUntilIdle()

        assertEquals(
            R.string.quick_prompt_error_name_duplicate,
            viewModel.uiStateFlow.value.formState.nameErrorResId,
        )
    }

    @Test
    fun save_rejectsReservedTriggerKeys() = runTest {
        installStore(quickPromptSettings())
        val viewModel = QuickPromptsSettingsViewModel()

        viewModel.sendIntent(QuickPromptsSettingsIntent.StartCreate)
        viewModel.sendIntent(QuickPromptsSettingsIntent.PromptChanged("Some prompt"))

        // 测试内置命令 new
        viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged("new"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
        advanceUntilIdle()

        assertEquals(
            R.string.quick_prompt_error_name_reserved,
            viewModel.uiStateFlow.value.formState.nameErrorResId,
        )

        // 测试分类前缀 skill
        viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged("skill"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
        advanceUntilIdle()

        assertEquals(
            R.string.quick_prompt_error_name_reserved,
            viewModel.uiStateFlow.value.formState.nameErrorResId,
        )
    }

    @Test
    fun save_acceptsHyphenUnderscoreAndUnicodeLetters() = runTest {
        installStore(quickPromptSettings())
        val viewModel = QuickPromptsSettingsViewModel()
        val effects = collectEffects(viewModel, 1)

        viewModel.sendIntent(QuickPromptsSettingsIntent.StartCreate)
        viewModel.sendIntent(QuickPromptsSettingsIntent.NameChanged("phone-use_1"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.PromptChanged("Phone use prompt"))
        viewModel.sendIntent(QuickPromptsSettingsIntent.Save)
        advanceUntilIdle()

        assertEquals(listOf(QuickPromptsSettingsEffect.ExitDetail), effects)
        val saved = XRepo.quickPrompts.list()
        assertEquals(1, saved.size)
        assertEquals("phone-use_1", saved.single().name)
        assertEquals("Phone use prompt", saved.single().prompt)
    }

    @Test
    fun delete_removesPrompt() = runTest {
        installStore(quickPromptSettings(samplePrompt("btw", "By the way")))
        val viewModel = QuickPromptsSettingsViewModel()
        viewModel.sendIntent(QuickPromptsSettingsIntent.Load)
        advanceUntilIdle()

        val effects = collectEffects(viewModel, 1)
        viewModel.sendIntent(QuickPromptsSettingsIntent.StartEdit(0))
        viewModel.sendIntent(QuickPromptsSettingsIntent.RequestDelete)
        advanceUntilIdle()

        assertNotNull(viewModel.uiStateFlow.value.deleteConfirmation)

        viewModel.sendIntent(QuickPromptsSettingsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertNull(viewModel.uiStateFlow.value.deleteConfirmation)
        assertEquals(listOf(QuickPromptsSettingsEffect.ExitDetail), effects)
        assertTrue(XRepo.quickPrompts.list().isEmpty())
    }

    private fun installStore(vararg initialJson: Pair<String, String>) {
        XRepo.installStoreForTest(FakeDomainSettingsStore(*initialJson))
        XRepo.init(context)
    }

    private fun quickPromptSettings(vararg prompts: RuntimeQuickPrompt): Pair<String, String> {
        return StoreDescriptorRegistry.PROMPTS_QUICK_ID to QuickPromptSettingsCodec.encodePrompts(prompts.toList())
    }

    private fun samplePrompt(name: String = "btw", prompt: String = "By the way"): RuntimeQuickPrompt {
        return RuntimeQuickPrompt(
            name = name,
            prompt = prompt,
            enabled = true,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectEffects(
        viewModel: QuickPromptsSettingsViewModel,
        count: Int,
    ): MutableList<QuickPromptsSettingsEffect> {
        val effects = mutableListOf<QuickPromptsSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
    }
}
