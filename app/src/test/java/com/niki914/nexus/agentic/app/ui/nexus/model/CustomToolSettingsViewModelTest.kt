package com.niki914.nexus.agentic.app.ui.nexus.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.FakeDomainSettingsStore
import com.niki914.nexus.agentic.repo.RuleSettingsCodec
import com.niki914.nexus.agentic.repo.ToolSettingsCodec
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

@OptIn(ExperimentalCoroutinesApi::class)
class CustomToolSettingsViewModelTest {
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
    fun save_rejectsBlankNameAndFocusesName() = runTest {
        installStore()
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.startCreateAndSave(
            name = " ",
            description = "Show battery state",
            command = "dumpsys battery",
        )
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(R.string.custom_tool_error_name_required, state.formState.nameErrorResId)
        assertNull(state.formState.descriptionErrorResId)
        assertNull(state.formState.commandErrorResId)
        assertEquals(listOf(CustomToolSettingsEffect.FocusName), effects)
        assertTrue(XRepo.customTools.list().isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun save_rejectsBlankDescriptionAndFocusesDescription() = runTest {
        installStore()
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.startCreateAndSave(
            name = "battery_status",
            description = " ",
            command = "dumpsys battery",
        )
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertNull(state.formState.nameErrorResId)
        assertEquals(
            R.string.custom_tool_error_description_required,
            state.formState.descriptionErrorResId,
        )
        assertNull(state.formState.commandErrorResId)
        assertEquals(listOf(CustomToolSettingsEffect.FocusDescription), effects)
        assertTrue(XRepo.customTools.list().isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun save_rejectsBlankCommandAndFocusesCommand() = runTest {
        installStore()
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.startCreateAndSave(
            name = "battery_status",
            description = "Show battery state",
            command = " ",
        )
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertNull(state.formState.nameErrorResId)
        assertNull(state.formState.descriptionErrorResId)
        assertEquals(R.string.custom_tool_error_command_required, state.formState.commandErrorResId)
        assertEquals(listOf(CustomToolSettingsEffect.FocusCommand), effects)
        assertTrue(XRepo.customTools.list().isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun save_createsToolAndExits() = runTest {
        installStore()
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.startCreateAndSave(
            name = " battery_status ",
            description = " Show battery state ",
            command = " dumpsys battery ",
        )
        advanceUntilIdle()

        assertEquals(
            listOf(CustomTool("battery_status", "Show battery state", "dumpsys battery")),
            XRepo.customTools.list(),
        )
        assertEquals(listOf(CustomToolSettingsEffect.ExitDetail), effects)
        assertFalse(viewModel.uiStateFlow.value.isSaving)
    }

    @Test
    fun save_renamesToolAndExits() = runTest {
        installStore(
            StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                listOf(
                    CustomTool("old_name", "Old description", "dumpsys battery"),
                    CustomTool("other_tool", "Other description", "settings list system"),
                ),
            )
        )
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(CustomToolSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(CustomToolSettingsIntent.StartEdit(0))
        viewModel.sendIntent(CustomToolSettingsIntent.NameChanged("new_name"))
        viewModel.sendIntent(CustomToolSettingsIntent.DescriptionChanged("New description"))
        viewModel.sendIntent(CustomToolSettingsIntent.CommandChanged("pm list packages"))
        viewModel.sendIntent(CustomToolSettingsIntent.EnabledChanged(false))
        viewModel.sendIntent(CustomToolSettingsIntent.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(
                CustomTool("other_tool", "Other description", "settings list system"),
                CustomTool("new_name", "New description", "pm list packages", enabled = false),
            ),
            XRepo.customTools.list(),
        )
        assertEquals(listOf(CustomToolSettingsEffect.ExitDetail), effects)
        assertFalse(viewModel.uiStateFlow.value.isSaving)
    }

    @Test
    fun save_mapsUnsafeCommandToCommandError() = runTest {
        installStore(
            StoreDescriptorRegistry.RULES_EXECUTION_ID to RuleSettingsCodec.encodeExecutionRules(
                listOf(
                    ExecutionRule(
                        id = "dangerous-delete",
                        name = "危险删改",
                        enabledMode = ExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("\\brm\\s+-rf\\b"),
                    )
                ),
            )
        )
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.startCreateAndSave(
            name = "wipe_data",
            description = "Dangerous command",
            command = "rm -rf /data/local/tmp/cache",
        )
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(null, state.formState.commandErrorResId)
        assertTrue(state.formState.commandErrorMessage.orEmpty().contains("危险删改"))
        assertEquals(listOf(CustomToolSettingsEffect.FocusCommand), effects)
        assertTrue(XRepo.customTools.list().isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun deleteCurrent_removesToolAndExits() = runTest {
        installStore(
            StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                listOf(CustomTool("battery_status", "Show battery state", "dumpsys battery")),
            )
        )
        val viewModel = CustomToolSettingsViewModel()
        val effects = collectEffects(viewModel, count = 1)

        viewModel.sendIntent(CustomToolSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(CustomToolSettingsIntent.StartEdit(0))
        viewModel.sendIntent(CustomToolSettingsIntent.RequestDelete)
        viewModel.sendIntent(CustomToolSettingsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(XRepo.customTools.list().isEmpty())
        assertEquals(listOf(CustomToolSettingsEffect.ExitDetail), effects)
        assertFalse(viewModel.uiStateFlow.value.isSaving)
    }

    @Test
    fun formState_tracksUnsavedChangesForCreateAndRestoredTrimmedFields() = runTest {
        installStore()
        val viewModel = CustomToolSettingsViewModel()

        viewModel.sendIntent(CustomToolSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(CustomToolSettingsIntent.StartCreate)
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)

        viewModel.sendIntent(CustomToolSettingsIntent.DescriptionChanged(" Show battery state "))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)

        viewModel.sendIntent(CustomToolSettingsIntent.DescriptionChanged(" "))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)
    }

    @Test
    fun formState_tracksUnsavedChangesForEditedEnabledAndRestoredValue() = runTest {
        installStore(
            StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                listOf(CustomTool("battery_status", "Show battery state", "dumpsys battery")),
            )
        )
        val viewModel = CustomToolSettingsViewModel()

        viewModel.sendIntent(CustomToolSettingsIntent.Load)
        advanceUntilIdle()
        viewModel.sendIntent(CustomToolSettingsIntent.StartEdit(0))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)

        viewModel.sendIntent(CustomToolSettingsIntent.EnabledChanged(false))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)

        viewModel.sendIntent(CustomToolSettingsIntent.EnabledChanged(true))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.formState.hasUnsavedChanges)
    }

    private fun installStore(vararg initialJson: Pair<String, String>) {
        XRepo.installStoreForTest(FakeDomainSettingsStore(*initialJson))
        XRepo.init(context)
    }

    private fun kotlinx.coroutines.test.TestScope.collectEffects(
        viewModel: CustomToolSettingsViewModel,
        count: Int,
    ): MutableList<CustomToolSettingsEffect> {
        val effects = mutableListOf<CustomToolSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffect.take(count).toList(effects)
        }
        return effects
    }

    private fun CustomToolSettingsViewModel.startCreateAndSave(
        name: String,
        description: String,
        command: String,
    ) {
        sendIntent(CustomToolSettingsIntent.Load)
        sendIntent(CustomToolSettingsIntent.StartCreate)
        sendIntent(CustomToolSettingsIntent.NameChanged(name))
        sendIntent(CustomToolSettingsIntent.DescriptionChanged(description))
        sendIntent(CustomToolSettingsIntent.CommandChanged(command))
        sendIntent(CustomToolSettingsIntent.Save)
    }
}
