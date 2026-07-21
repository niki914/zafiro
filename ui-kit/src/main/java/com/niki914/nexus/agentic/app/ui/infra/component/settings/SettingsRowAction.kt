package com.niki914.nexus.agentic.app.ui.infra.component.settings

sealed interface SettingsRowAction {
    data class Navigate(val id: String) : SettingsRowAction
    data class ToggleChanged(val id: String, val checked: Boolean) : SettingsRowAction
    data class Click(val id: String) : SettingsRowAction
}
