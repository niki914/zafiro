package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.content.mcp.McpSettingsContent
import com.niki914.zafiro.app.ui.model.SettingsViewModel
import com.niki914.zafiro.app.ui.nav.BuiltinToolGroupDetailPage
import com.niki914.zafiro.app.ui.nav.ExecutionRuleDetailPage
import com.niki914.zafiro.app.ui.nav.McpServerDetailPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup
import com.niki914.zafiro.app.ui.nav.SettingsProviderPickPage
import com.niki914.zafiro.app.ui.nav.SkillDetailPage
import com.niki914.zafiro.app.ui.nav.TakeoverRuleDetailPage

@Composable
fun SettingsDetailPageContent(
    group: ZafiroSettingsGroup,
    onPush: (ZafiroPage) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<SettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val visibleGroups = uiState.sections.flatMap { it.groups }.toSet()
    if (group !in visibleGroups) {
        return
    }

    if (group == ZafiroSettingsGroup.ModelConfig) {
        ModelConfigSettingsContent(
            onBack = onBack,
            onOpenProviderPick = {
                onPush(SettingsProviderPickPage)
            },
        )
        return
    }

    if (group == ZafiroSettingsGroup.BuiltinTools) {
        BuiltinToolsSettingsContent(
            onOpenGroupDetail = { groupId ->
                onPush(BuiltinToolGroupDetailPage(groupId))
            },
        )
        return
    }

    if (group == ZafiroSettingsGroup.Skills) {
        SkillsSettingsContent(
            onOpenSkillDetail = { id, title ->
                onPush(SkillDetailPage(id, title))
            },
        )
        return
    }

    if (group == ZafiroSettingsGroup.Mcp) {
        McpSettingsContent(
            onOpenServerDetail = { name, index, isCreating ->
                onPush(McpServerDetailPage(name, index, isCreating))
            },
        )
        return
    }

    if (group == ZafiroSettingsGroup.About) {
        AboutSettingsContent()
        return
    }

    if (group == ZafiroSettingsGroup.Memory) {
        MemorySettingsContent()
        return
    }

    if (group == ZafiroSettingsGroup.Takeover) {
        TakeoverSettingsContent(
            onOpenRuleDetail = { id, name, index, isCreating ->
                onPush(
                    TakeoverRuleDetailPage(
                        ruleId = id,
                        ruleName = name,
                        ruleIndex = index,
                        isCreating = isCreating,
                    )
                )
            },
        )
        return
    }

    if (group == ZafiroSettingsGroup.ExecutionRules) {
        ExecutionRulesSettingsContent(
            onOpenRuleDetail = { name, index, isCreating ->
                onPush(ExecutionRuleDetailPage(name, index, isCreating))
            },
        )
        return
    }

    TODOPageContent()
    return
}
