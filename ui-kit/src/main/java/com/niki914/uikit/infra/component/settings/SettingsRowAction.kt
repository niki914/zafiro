package com.niki914.uikit.infra.component.settings

sealed interface SettingsRowAction {
    data class Navigate(val id: String) : SettingsRowAction
    data class ToggleChanged(val id: String, val checked: Boolean) : SettingsRowAction
    data class Click(val id: String) : SettingsRowAction
    data class SliderChanged(val id: String, val value: Float) : SettingsRowAction
    data class SliderCommit(val id: String, val value: Float) : SettingsRowAction
}
