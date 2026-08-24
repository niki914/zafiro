package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.model.SettingsUiState
import com.niki914.zafiro.app.ui.model.SettingsViewModel
import com.niki914.zafiro.app.ui.model.buildSettingsUiState
import com.niki914.zafiro.app.ui.nav.NexusSettingsGroup
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
                NexusSettingsGroup.Memory,
                NexusSettingsGroup.Mcp,
                NexusSettingsGroup.About,
            )
        )

        assertEquals(3, state.sections.size)
        assertEquals(
            listOf(NexusSettingsGroup.ModelConfig),
            state.sections[0].groups,
        )
        assertEquals(
            listOf(
                NexusSettingsGroup.BuiltinTools,
                NexusSettingsGroup.Skills,
                NexusSettingsGroup.CustomShellTools,
            ),
            state.sections[1].groups,
        )
        assertEquals(
            listOf(
                NexusSettingsGroup.Takeover,
                NexusSettingsGroup.ExecutionRules,
            ),
            state.sections[2].groups,
        )
    }

    @Test
    fun settingsViewModel_usesEmptyDefaultHiddenGroups() {
        val viewModel = SettingsViewModel()
        val state = viewModel.uiStateFlow.value

        assertTrue(state.isGroupVisible(NexusSettingsGroup.ModelConfig))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.Memory))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.BuiltinTools))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.Skills))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.CustomShellTools))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.Mcp))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.Takeover))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.ExecutionRules))
        assertTrue(state.isGroupVisible(NexusSettingsGroup.About))
    }
}

private fun SettingsUiState.isGroupVisible(group: NexusSettingsGroup): Boolean {
    return sections.any { section -> group in section.groups }
}
