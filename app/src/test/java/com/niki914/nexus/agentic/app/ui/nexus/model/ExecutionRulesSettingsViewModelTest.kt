package com.niki914.nexus.agentic.app.ui.nexus.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.FakeDomainSettingsStore
import com.niki914.nexus.agentic.repo.LocalSettingsDefaults
import com.niki914.nexus.agentic.repo.RuleSettingsCodec
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.store.StoreDescriptorRegistry
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
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionRulesSettingsViewModelTest {
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
        installStore(rulesSettings(sampleRule()))
        val viewModel = ExecutionRulesSettingsViewModel()

        viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals("危险删改", state.items.single().name)
        assertEquals(ExecutionRuleEnabledMode.LOCKED_ONLY, state.items.single().enabledMode)
    }

    @Test
    fun itemEnabledChanged_updatesEnabledMode() = runTest {
        installStore(rulesSettings(sampleRule()))
        val viewModel = ExecutionRulesSettingsViewModel()

        viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(ExecutionRulesSettingsIntent.ItemEnabledChanged(0, false))
        advanceUntilIdle()

        assertEquals(
            ExecutionRuleEnabledMode.DISABLED,
            XRepo.executionRules.list().single().enabledMode
        )
        assertEquals(
            ExecutionRuleEnabledMode.DISABLED,
            viewModel.uiStateFlow.value.items.single().enabledMode
        )
    }

    @Test
    fun save_createsRuleAndExits() = runTest {
        installStore(rulesSettings(LocalSettingsDefaults.defaultExecutionRules))
        val viewModel = ExecutionRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
        viewModel.sendIntent(ExecutionRulesSettingsIntent.StartCreate)
        viewModel.sendIntent(ExecutionRulesSettingsIntent.NameChanged(" 新规则 "))
        viewModel.sendIntent(ExecutionRulesSettingsIntent.PatternsChanged("\n echo danger \n\n"))
        viewModel.sendIntent(ExecutionRulesSettingsIntent.Save)
        advanceUntilIdle()

        val rule = XRepo.executionRules.list().single { it.name == "新规则" }
        assertEquals("新规则", rule.name)
        assertEquals(listOf("echo danger"), rule.patterns)
        assertEquals(
            LocalSettingsDefaults.defaultExecutionRules.size + 1,
            XRepo.executionRules.list().size
        )
        assertEquals(listOf(ExecutionRulesSettingsEffect.ExitDetail), effects)
        assertFalse(viewModel.uiStateFlow.value.isSaving)
    }

    @Test
    fun save_invalidFields_setsFieldErrorsAndFocusesFirstInvalidField() = runTest {
        installStore()
        val viewModel = ExecutionRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 2)

        viewModel.sendIntent(ExecutionRulesSettingsIntent.StartCreate)
        viewModel.sendIntent(ExecutionRulesSettingsIntent.Save)
        advanceUntilIdle()

        var formState = viewModel.uiStateFlow.value.formState
        assertEquals(R.string.execution_rules_error_name_required, formState.nameErrorResId)
        assertEquals(R.string.execution_rules_error_patterns_required, formState.patternsErrorResId)

        viewModel.sendIntent(ExecutionRulesSettingsIntent.NameChanged("危险删改"))
        viewModel.sendIntent(ExecutionRulesSettingsIntent.Save)
        advanceUntilIdle()

        formState = viewModel.uiStateFlow.value.formState
        assertEquals(null, formState.nameErrorResId)
        assertEquals(R.string.execution_rules_error_patterns_required, formState.patternsErrorResId)
        assertEquals(
            listOf(
                ExecutionRulesSettingsEffect.FocusName,
                ExecutionRulesSettingsEffect.FocusPatterns,
            ),
            effects,
        )
    }

    @Test
    fun save_editsExistingRuleAndRequestDeleteRemovesIt() = runTest {
        installStore(rulesSettings(sampleRule()))
        val viewModel = ExecutionRulesSettingsViewModel()
        val effects = collectEffects(viewModel, count = 2)

        viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(ExecutionRulesSettingsIntent.StartEdit(0))
        viewModel.sendIntent(ExecutionRulesSettingsIntent.NameChanged("更新规则"))
        viewModel.sendIntent(
            ExecutionRulesSettingsIntent.EnabledModeChanged(
                ExecutionRuleEnabledMode.ALWAYS
            )
        )
        viewModel.sendIntent(ExecutionRulesSettingsIntent.PatternsChanged("pm uninstall"))
        viewModel.sendIntent(ExecutionRulesSettingsIntent.Save)
        advanceUntilIdle()

        val updated = XRepo.executionRules.list().single()
        assertEquals("builtin-dangerous-delete", updated.id)
        assertEquals("更新规则", updated.name)
        assertEquals(ExecutionRuleEnabledMode.ALWAYS, updated.enabledMode)
        assertEquals(listOf("pm uninstall"), updated.patterns)

        viewModel.sendIntent(ExecutionRulesSettingsIntent.RequestDelete)
        viewModel.sendIntent(ExecutionRulesSettingsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(XRepo.executionRules.list().isEmpty())
        assertEquals(
            listOf(
                ExecutionRulesSettingsEffect.ExitDetail,
                ExecutionRulesSettingsEffect.ExitDetail,
            ),
            effects,
        )
    }

    private fun installStore(vararg initialJson: Pair<String, String>) {
        XRepo.installStoreForTest(FakeDomainSettingsStore(*initialJson))
        XRepo.init(context)
    }

    private fun rulesSettings(vararg rules: ExecutionRule): Pair<String, String> {
        return StoreDescriptorRegistry.RULES_EXECUTION_ID to RuleSettingsCodec.encodeExecutionRules(
            rules.toList()
        )
    }

    private fun rulesSettings(rules: List<ExecutionRule>): Pair<String, String> {
        return StoreDescriptorRegistry.RULES_EXECUTION_ID to RuleSettingsCodec.encodeExecutionRules(
            rules
        )
    }

    private fun sampleRule(): ExecutionRule {
        return ExecutionRule(
            id = "builtin-dangerous-delete",
            name = "危险删改",
            enabledMode = ExecutionRuleEnabledMode.LOCKED_ONLY,
            patterns = listOf("\\brm\\s+-rf\\b"),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectEffects(
        viewModel: ExecutionRulesSettingsViewModel,
        count: Int,
    ): MutableList<ExecutionRulesSettingsEffect> {
        val effects = mutableListOf<ExecutionRulesSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
    }
}
