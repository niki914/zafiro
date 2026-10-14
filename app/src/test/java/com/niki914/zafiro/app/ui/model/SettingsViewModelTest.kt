package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup
import com.niki914.zafiro.app.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// 保护：通用设置的分组列表与隐藏分组过滤（改了分组成员/顺序而没同步隐藏规则会被拦下）。
class SettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @Test
    fun buildSettingsUiState_excludesHiddenGroupsAndDropsEmptySections() {
        val state = buildSettingsUiState(
            hiddenGroups = setOf(
                ZafiroSettingsGroup.Memory,
                ZafiroSettingsGroup.Mcp,
                ZafiroSettingsGroup.McpService,
                ZafiroSettingsGroup.About,
            )
        )

        assertEquals(5, state.sections.size)
        assertEquals(
            listOf(ZafiroSettingsGroup.ModelConfig, ZafiroSettingsGroup.RuntimeControl),
            state.sections[0].groups,
        )
        assertEquals(
            listOf(ZafiroSettingsGroup.Theme, ZafiroSettingsGroup.GeneralSettings),
            state.sections[1].groups,
        )
        assertEquals(
            listOf(
                ZafiroSettingsGroup.Tools,
                ZafiroSettingsGroup.Skills,
                ZafiroSettingsGroup.Actions,
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
