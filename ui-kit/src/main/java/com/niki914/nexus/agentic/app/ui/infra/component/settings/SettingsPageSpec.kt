package com.niki914.nexus.agentic.app.ui.infra.component.settings

data class SettingsPageSpec(
    val description: String? = null,
    val sections: List<SettingsSectionSpec> = emptyList(),
)

data class SettingsSectionSpec(
    val title: String? = null,
    val layout: SettingsSectionLayout = SettingsSectionLayout.GroupedCard,
    val rows: List<SettingsRowSpec> = emptyList(),
)

enum class SettingsSectionLayout {
    GroupedCard,
    CardList,
}
