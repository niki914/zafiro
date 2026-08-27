package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup
import com.niki914.uikit.base.ComposeMVIViewModel

data class SettingsSectionUiState(
    @StringRes val titleRes: Int,
    val groups: List<ZafiroSettingsGroup>,
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
    val groups: List<ZafiroSettingsGroup>,
)

internal fun buildSettingsUiState(
    hiddenGroups: Set<ZafiroSettingsGroup>,
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
                ZafiroSettingsGroup.ModelConfig,
                ZafiroSettingsGroup.Memory,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_tools,
            groups = listOf(
                ZafiroSettingsGroup.BuiltinTools,
                ZafiroSettingsGroup.Skills,
                ZafiroSettingsGroup.PyTools,
                ZafiroSettingsGroup.Mcp,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_rules,
            groups = listOf(
                ZafiroSettingsGroup.Takeover,
                ZafiroSettingsGroup.ExecutionRules,
            ),
        ),
        SettingsSectionDefinition(
            titleRes = R.string.ui_settings_section_app,
            groups = listOf(
                ZafiroSettingsGroup.About,
            ),
        ),
    )
}

private fun defaultHiddenSettingsGroups(): Set<ZafiroSettingsGroup> {
    return emptySet()
}
