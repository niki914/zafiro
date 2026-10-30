// 保护：数据脱敏规则的增删改查状态机与表单校验流转（改坏字段校验或持久化映射时拦截）
package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.LocalSettingsDefaults
import com.niki914.zafiro.repo.RuleSettingsCodec
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeMaskingRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaskingRulesSettingsViewModelTest {
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
    fun load_readsRealRules() = runTest {
        installStore(maskingSettings(sampleRule()))
        val viewModel = MaskingRulesSettingsViewModel()

        viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals("API密钥脱敏", state.items.single().name)
        assertTrue(state.items.single().enabled)
        assertEquals("API_KEY", state.items.single().kind)
    }

    @Test
    fun itemEnabledChanged_updatesEnabled() = runTest {
        installStore(maskingSettings(sampleRule()))
        val viewModel = MaskingRulesSettingsViewModel()

        viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(MaskingRulesSettingsIntent.ItemEnabledChanged(0, false))
        advanceUntilIdle()

        assertFalse(XRepo.maskingRules.list().single().enabled)
        assertFalse(viewModel.uiStateFlow.value.items.single().enabled)
    }

    @Test
    fun save_createsRuleAndExits() = runTest {
        installStore(maskingSettings(LocalSettingsDefaults.defaultMaskingRules))
        val viewModel = MaskingRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
        viewModel.sendIntent(MaskingRulesSettingsIntent.StartCreate)
        assertEquals("", viewModel.uiStateFlow.value.formState.kind)

        viewModel.sendIntent(MaskingRulesSettingsIntent.NameChanged(" 新邮箱规则 "))
        viewModel.sendIntent(MaskingRulesSettingsIntent.KindChanged(" 敏感 信息 "))
        viewModel.sendIntent(MaskingRulesSettingsIntent.PatternsChanged("\n [\\w.-]+@[\\w.-]+\\.\\w+ \n\n"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
        advanceUntilIdle()

        val rules = XRepo.maskingRules.list()
        val rule = rules.single { it.name == "新邮箱规则" }
        assertEquals("新邮箱规则", rule.name)
        assertEquals("敏感 信息", rule.kind)
        assertEquals(listOf("[\\w.-]+@[\\w.-]+\\.\\w+"), rule.patterns)
        assertEquals(LocalSettingsDefaults.defaultMaskingRules.size + 1, rules.size)
        assertEquals(listOf(MaskingRulesSettingsEffect.ExitDetail), effects)
        assertFalse(viewModel.uiStateFlow.value.isSaving)
    }

    @Test
    fun save_invalidFields_setsFieldErrorsAndFocusesFirstInvalidField() = runTest {
        installStore()
        val viewModel = MaskingRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 3)

        viewModel.sendIntent(MaskingRulesSettingsIntent.StartCreate)
        viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
        advanceUntilIdle()

        var formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.masking_rules_error_name_required, formState.nameErrorResId)
        assertEquals(R.string.masking_rules_error_kind_required, formState.kindErrorResId)
        assertEquals(R.string.masking_rules_error_patterns_required, formState.patternsErrorResId)

        viewModel.sendIntent(MaskingRulesSettingsIntent.NameChanged("脱敏规则"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.KindChanged("}} DO STH {{"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(null, formState.nameErrorResId)
        assertEquals(R.string.masking_rules_error_kind_invalid, formState.kindErrorResId)
        assertEquals(R.string.masking_rules_error_patterns_required, formState.patternsErrorResId)

        viewModel.sendIntent(MaskingRulesSettingsIntent.KindChanged("合法类型"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(null, formState.nameErrorResId)
        assertEquals(null, formState.kindErrorResId)
        assertEquals(R.string.masking_rules_error_patterns_required, formState.patternsErrorResId)
        assertEquals(
            listOf(
                MaskingRulesSettingsEffect.FocusName,
                MaskingRulesSettingsEffect.FocusKind,
                MaskingRulesSettingsEffect.FocusPatterns,
            ),
            effects,
        )
    }

    @Test
    fun save_editsExistingRuleAndRequestDeleteRemovesIt() = runTest {
        installStore(maskingSettings(sampleRule()))
        val viewModel = MaskingRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 2)

        viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(MaskingRulesSettingsIntent.StartEdit("masking-1"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.NameChanged("更新后名称"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.KindChanged("CUSTOM_KIND"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.PatternsChanged("secret-[0-9]+"))
        viewModel.sendIntent(MaskingRulesSettingsIntent.Save)
        advanceUntilIdle()

        val updated = XRepo.maskingRules.list().single()
        assertEquals("masking-1", updated.id)
        assertEquals("更新后名称", updated.name)
        assertEquals("CUSTOM_KIND", updated.kind)
        assertEquals(listOf("secret-[0-9]+"), updated.patterns)

        viewModel.sendIntent(MaskingRulesSettingsIntent.RequestDelete)
        viewModel.sendIntent(MaskingRulesSettingsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(XRepo.maskingRules.list().isEmpty())
        assertEquals(
            listOf(
                MaskingRulesSettingsEffect.ExitDetail,
                MaskingRulesSettingsEffect.ExitDetail,
            ),
            effects,
        )
    }

    private fun installStore(vararg initialJson: Pair<String, String>) {
        XRepo.installStoreForTest(FakeDomainSettingsStore(*initialJson))
        XRepo.init(context)
    }

    private fun maskingSettings(vararg rules: RuntimeMaskingRule): Pair<String, String> {
        return StoreDescriptorRegistry.RULES_MASKING_ID to RuleSettingsCodec.encodeMaskingRules(rules.toList())
    }

    private fun maskingSettings(rules: List<RuntimeMaskingRule>): Pair<String, String> {
        return StoreDescriptorRegistry.RULES_MASKING_ID to RuleSettingsCodec.encodeMaskingRules(rules)
    }

    private fun sampleRule(): RuntimeMaskingRule {
        return RuntimeMaskingRule(
            id = "masking-1",
            name = "API密钥脱敏",
            enabled = true,
            kind = "API_KEY",
            patterns = listOf("""\bsk-[a-zA-Z0-9_\-]{20,}\b"""),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectEffects(
        viewModel: MaskingRulesSettingsViewModel,
        count: Int,
    ): MutableList<MaskingRulesSettingsEffect> {
        val effects = mutableListOf<MaskingRulesSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
    }
}
