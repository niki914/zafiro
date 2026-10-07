package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup
import com.niki914.zafiro.app.util.SilentLoggerRule
import org.junit.Assert.assertEquals
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

        assertEquals(5, state.sections.size)
        assertEquals(
            listOf(ZafiroSettingsGroup.ModelConfig),
            state.sections[0].groups,
        )
        assertEquals(
            listOf(ZafiroSettingsGroup.GeneralSettings),
            state.sections[1].groups,
        )
        assertEquals(
            listOf(
                ZafiroSettingsGroup.Tools,
                ZafiroSettingsGroup.Skills,
            ),
            state.sections[2].groups,
        )
        assertEquals(
            listOf(
                ZafiroSettingsGroup.Takeover,
                ZafiroSettingsGroup.ExecutionRules,
            ),
            state.sections[3].groups,
        )
        assertEquals(
            listOf(ZafiroSettingsGroup.Storage),
            state.sections[4].groups,
        )
    }

}

private fun SettingsUiState.isGroupVisible(group: ZafiroSettingsGroup): Boolean {
    return sections.any { section -> group in section.groups }
}
