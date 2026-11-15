// 保护：环境变量与上下文注入的表单校验与状态流转（改坏键名保留字/格式校验或增删改查时拦截）
package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.LlmConfigsSettingsCodec
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvContextSettingsViewModelTest {
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
    fun load_readsPersistedConfig() = runTest {
        installStore(
            RuntimeEnvContextConfig(
                enabled = true,
                keys = setOf(RuntimeEnvContextConfig.KEY_TIME, "role"),
                variables = mapOf("role" to "Developer"),
            )
        )
        val viewModel = EnvContextSettingsViewModel()

        viewModel.sendIntent(EnvContextSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertTrue(state.enabled)
        assertTrue(state.keys.contains("time"))
        assertTrue(state.keys.contains("role"))
        assertEquals("Developer", state.variables["role"])
    }

    @Test
    fun saveVariable_validatesKeyAndValueRules() = runTest {
        installStore(
            RuntimeEnvContextConfig(
                enabled = true,
                keys = emptySet(),
                variables = mapOf("existing_key" to "value1"),
            )
        )
        val viewModel = EnvContextSettingsViewModel()
        val effects = collectEffects(viewModel, count = 5)

        viewModel.sendIntent(EnvContextSettingsIntent.Load)
        advanceUntilIdle()

        // 1. 尝试保存空键名
        viewModel.sendIntent(EnvContextSettingsIntent.StartCreateVariable)
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        var formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.ui_settings_env_context_error_key_required, formState.keyErrorResId)

        // 2. 尝试使用保留键名 (date)
        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("date"))
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.ui_settings_env_context_error_key_reserved, formState.keyErrorResId)

        // 3. 尝试包含非法字符（管道符与空格）
        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("my|key"))
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.ui_settings_env_context_error_key_invalid_format, formState.keyErrorResId)

        // 4. 尝试重名键名
        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("existing_key"))
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.ui_settings_env_context_error_key_duplicate, formState.keyErrorResId)

        // 5. 合法键名，但值为空
        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("role"))
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertNull(formState.keyErrorResId)
        assertEquals(R.string.ui_settings_env_context_error_value_required, formState.valueErrorResId)

        assertEquals(
            listOf(
                EnvContextSettingsEffect.FocusKey,
                EnvContextSettingsEffect.FocusKey,
                EnvContextSettingsEffect.FocusKey,
                EnvContextSettingsEffect.FocusKey,
                EnvContextSettingsEffect.FocusValue,
            ),
            effects,
        )
    }

    @Test
    fun saveAndEditAndRemoveVariable_updatesStateAndPersists() = runTest {
        installStore(RuntimeEnvContextConfig(enabled = true))
        val viewModel = EnvContextSettingsViewModel()
        val effects = collectEffects(viewModel, count = 3)

        viewModel.sendIntent(EnvContextSettingsIntent.Load)
        advanceUntilIdle()

        // 1. 新建并保存变量
        viewModel.sendIntent(EnvContextSettingsIntent.StartCreateVariable)
        viewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("role"))
        viewModel.sendIntent(EnvContextSettingsIntent.ValueInputChanged("Developer"))
        viewModel.sendIntent(EnvContextSettingsIntent.EnabledInputChanged(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        var persisted = XRepo.llmConfigs.envContext()
        assertEquals("Developer", persisted.variables["role"])
        assertTrue(persisted.keys.contains("role"))

        // 2. 编辑现有变量值
        viewModel.sendIntent(EnvContextSettingsIntent.StartEditVariable("role"))
        viewModel.sendIntent(EnvContextSettingsIntent.ValueInputChanged("Architect"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)
        viewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        persisted = XRepo.llmConfigs.envContext()
        assertEquals("Architect", persisted.variables["role"])

        // 3. 删除变量
        viewModel.sendIntent(EnvContextSettingsIntent.StartEditVariable("role"))
        viewModel.sendIntent(EnvContextSettingsIntent.RequestDeleteVariable)
        viewModel.sendIntent(EnvContextSettingsIntent.ConfirmDeleteVariable)
        advanceUntilIdle()

        persisted = XRepo.llmConfigs.envContext()
        assertFalse(persisted.variables.containsKey("role"))
        assertFalse(persisted.keys.contains("role"))

        assertEquals(
            listOf(
                EnvContextSettingsEffect.ExitDetail,
                EnvContextSettingsEffect.ExitDetail,
                EnvContextSettingsEffect.ExitDetail,
            ),
            effects,
        )
    }

    @Test
    fun saveVariable_fromSecondaryInstance_preservesMasterToggleAndBuiltinKeys() = runTest {
        installStore(
            RuntimeEnvContextConfig(
                enabled = true,
                keys = setOf(
                    RuntimeEnvContextConfig.KEY_DATE,
                    RuntimeEnvContextConfig.KEY_TIME,
                    RuntimeEnvContextConfig.KEY_DEVICE,
                ),
            )
        )
        val listViewModel = EnvContextSettingsViewModel()
        listViewModel.sendIntent(EnvContextSettingsIntent.Load)
        advanceUntilIdle()

        // 模拟详情页的独立 ViewModel 实例
        val detailViewModel = EnvContextSettingsViewModel()
        detailViewModel.sendIntent(EnvContextSettingsIntent.StartCreateVariable)
        detailViewModel.sendIntent(EnvContextSettingsIntent.KeyInputChanged("role"))
        detailViewModel.sendIntent(EnvContextSettingsIntent.ValueInputChanged("Developer"))
        detailViewModel.sendIntent(EnvContextSettingsIntent.EnabledInputChanged(true))
        detailViewModel.sendIntent(EnvContextSettingsIntent.SaveVariable)
        advanceUntilIdle()

        // 验证持久化层：总开关未被覆写为 false，内置 keys 未丢失
        val persisted = XRepo.llmConfigs.envContext()
        assertTrue(persisted.enabled)
        assertTrue(persisted.keys.contains(RuntimeEnvContextConfig.KEY_DATE))
        assertTrue(persisted.keys.contains(RuntimeEnvContextConfig.KEY_TIME))
        assertTrue(persisted.keys.contains(RuntimeEnvContextConfig.KEY_DEVICE))
        assertTrue(persisted.keys.contains("role"))
        assertEquals("Developer", persisted.variables["role"])

        // 验证列表页 ViewModel 收到广播并自动更新
        val listState = listViewModel.uiStateFlow.value
        assertTrue(listState.enabled)
        assertTrue(listState.keys.contains(RuntimeEnvContextConfig.KEY_DATE))
        assertTrue(listState.keys.contains("role"))
        assertEquals("Developer", listState.variables["role"])
    }

    private fun installStore(config: RuntimeEnvContextConfig) {
        val doc = LlmConfigsDocument(
            prompt = "",
            envContext = config,
        )
        val json = LlmConfigsSettingsCodec.encode(doc)
        XRepo.installStoreForTest(FakeDomainSettingsStore(StoreDescriptorRegistry.LLM_CONFIGS_ID to json))
        XRepo.init(context)
    }

    private fun collectEffects(
        viewModel: EnvContextSettingsViewModel,
        count: Int,
    ): List<EnvContextSettingsEffect> {
        val results = mutableListOf<EnvContextSettingsEffect>()
        kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()).launch {
            viewModel.uiEffect.take(count).toList(results)
        }
        return results
    }
}
