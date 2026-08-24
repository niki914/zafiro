package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup
import com.niki914.zafiro.app.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @Test
    fun buildSettingsUiState_excludesHiddenGroupsAndDropsEmptySections() {
        val state = buildSettingsUiState(
            hiddenGroups = setOf(
                ZafiroSettingsGroup.Memory,
                ZafiroSettingsGroup.Mcp,
                ZafiroSettingsGroup.About,
            )
        )

        assertEquals(3, state.sections.size)
        assertEquals(
            listOf(ZafiroSettingsGroup.ModelConfig),
            state.sections[0].groups,
        )
        assertEquals(
            listOf(
                ZafiroSettingsGroup.BuiltinTools,
                ZafiroSettingsGroup.Skills,
                ZafiroSettingsGroup.CustomShellTools,
            ),
            state.sections[1].groups,
        )
        assertEquals(
            listOf(
                ZafiroSettingsGroup.Takeover,
                ZafiroSettingsGroup.ExecutionRules,
            ),
            state.sections[2].groups,
        )
    }

    @Test
    fun settingsViewModel_usesEmptyDefaultHiddenGroups() {
        val viewModel = SettingsViewModel()
        val state = viewModel.uiStateFlow.value

        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.ModelConfig))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.Memory))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.BuiltinTools))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.Skills))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.CustomShellTools))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.Mcp))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.Takeover))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.ExecutionRules))
        assertTrue(state.isGroupVisible(ZafiroSettingsGroup.About))
    }
}

private fun SettingsUiState.isGroupVisible(group: ZafiroSettingsGroup): Boolean {
    return sections.any { section -> group in section.groups }
}
