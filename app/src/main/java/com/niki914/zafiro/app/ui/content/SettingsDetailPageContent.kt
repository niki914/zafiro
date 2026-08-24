package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.content.mcp.McpSettingsContent
import com.niki914.zafiro.app.ui.model.SettingsViewModel
import com.niki914.zafiro.app.ui.nav.CustomToolDetailPage
import com.niki914.zafiro.app.ui.nav.ExecutionRuleDetailPage
import com.niki914.zafiro.app.ui.nav.McpServerDetailPage
import com.niki914.zafiro.app.ui.nav.NexusPage
import com.niki914.zafiro.app.ui.nav.NexusSettingsGroup
import com.niki914.zafiro.app.ui.nav.SettingsProviderPickPage
import com.niki914.zafiro.app.ui.nav.SkillDetailPage
import com.niki914.zafiro.app.ui.nav.TakeoverRuleDetailPage

@Composable
fun SettingsDetailPageContent(
    group: NexusSettingsGroup,
    onPush: (NexusPage) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<SettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val visibleGroups = uiState.sections.flatMap { it.groups }.toSet()
    if (group !in visibleGroups) {
        return
    }

    if (group == NexusSettingsGroup.ModelConfig) {
        ModelConfigSettingsContent(
            onBack = onBack,
            onOpenProviderPick = {
                onPush(SettingsProviderPickPage)
            },
        )
        return
    }

    if (group == NexusSettingsGroup.BuiltinTools) {
        BuiltinToolsSettingsContent()
        return
    }

    if (group == NexusSettingsGroup.Skills) {
        SkillsSettingsContent(
            onOpenSkillDetail = { id, title ->
                onPush(SkillDetailPage(id, title))
            },
        )
        return
    }

    if (group == NexusSettingsGroup.CustomShellTools) {
        CustomShellToolsSettingsContent(
            onOpenToolDetail = { name, index, isCreating ->
                onPush(CustomToolDetailPage(name, index, isCreating))
            },
        )
        return
    }

    if (group == NexusSettingsGroup.Mcp) {
        McpSettingsContent(
            onOpenServerDetail = { name, index, isCreating ->
                onPush(McpServerDetailPage(name, index, isCreating))
            },
        )
        return
    }

    if (group == NexusSettingsGroup.About) {
        AboutSettingsContent()
        return
    }

    if (group == NexusSettingsGroup.Memory) {
        MemorySettingsContent()
        return
    }

    if (group == NexusSettingsGroup.Takeover) {
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

    if (group == NexusSettingsGroup.ExecutionRules) {
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
