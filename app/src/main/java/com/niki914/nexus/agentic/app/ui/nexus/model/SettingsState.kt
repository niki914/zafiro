package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.nexus.nav.NexusSettingsGroup
import com.niki914.nexus.base.ComposeMVIViewModel

data class SettingsSectionUiState(
    @StringRes val titleRes: Int,
    val groups: List<NexusSettingsGroup>,
)

data class SettingsUiState(
    val sections: List<SettingsSectionUiState> = emptyList(),
)

sealed interface SettingsIntent

sealed interface SettingsEffect

class SettingsViewModel : ComposeMVIViewModel<SettingsIntent, SettingsUiState, SettingsEffect>() {

    override fun initUiState(): SettingsUiState {
        val state = buildSettingsUiState(defaultHiddenSettingsGroups())
        Logger.d(
            LOG_TAG,
            "initUiState sections=${state.sections.size} " +
                "groups=${state.sections.sumOf { it.groups.size }}"
        )
        return state
    }

    override suspend fun handleIntent(intent: SettingsIntent) = Unit

    private companion object {
        private const val LOG_TAG = "niki914_nexus_SettingsViewModel"
    }
}

private data class SettingsSectionDefinition(
    @StringRes val titleRes: Int,
    val groups: List<NexusSettingsGroup>,
)

internal fun buildSettingsUiState(
    hiddenGroups: Set<NexusSettingsGroup>,
): SettingsUiState {
    return SettingsUiState(
        sections = settingsSections()
            .mapNotNull { section ->
                val groups = section.groups.filterNot(hiddenGroups::contains)
                groups.takeIf { it.isNotEmpty() }?.let {
                    SettingsSectionUiState(
                        titleRes = section.titleRes,
                        groups = groups,
                    )
                }
            }
    )
}

private fun settingsSections(): List<SettingsSectionDefinition> {
    return listOf(
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_model,
            groups = listOf(
                NexusSettingsGroup.ModelConfig,
                NexusSettingsGroup.Memory,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_tools,
            groups = listOf(
                NexusSettingsGroup.BuiltinTools,
                NexusSettingsGroup.Skills,
                NexusSettingsGroup.CustomShellTools,
                NexusSettingsGroup.Mcp,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_rules,
            groups = listOf(
                NexusSettingsGroup.Takeover,
                NexusSettingsGroup.ExecutionRules,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_app,
            groups = listOf(
                NexusSettingsGroup.About,
            ),
        ),
    )
}

private fun defaultHiddenSettingsGroups(): Set<NexusSettingsGroup> {
    return emptySet()
}
