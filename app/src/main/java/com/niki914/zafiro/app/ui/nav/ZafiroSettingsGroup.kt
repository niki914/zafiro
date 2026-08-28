package com.niki914.zafiro.app.ui.nav

import androidx.annotation.StringRes
import com.niki914.zafiro.app.R

enum class ZafiroSettingsGroup(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val routeSuffix: String,
) {
    ModelConfig(
        titleRes = R.string.ui_settings_model_config,
        summaryRes = R.string.ui_settings_model_config_summary,
        routeSuffix = "model-config",
    ),
    Memory(
        titleRes = R.string.ui_settings_memory,
        summaryRes = R.string.ui_settings_memory_summary,
        routeSuffix = "memory",
    ),
    BuiltinTools(
        titleRes = R.string.ui_settings_builtin_tools,
        summaryRes = R.string.ui_settings_builtin_tools_summary,
        routeSuffix = "builtin-tools",
    ),
    Skills(
        titleRes = R.string.ui_settings_skills,
        summaryRes = R.string.ui_settings_skills_summary,
        routeSuffix = "skills",
    ),
    Mcp(
        titleRes = R.string.ui_settings_mcp,
        summaryRes = R.string.ui_settings_mcp_summary,
        routeSuffix = "mcp",
    ),
    Takeover(
        titleRes = R.string.ui_settings_takeover,
        summaryRes = R.string.ui_settings_takeover_summary,
        routeSuffix = "takeover",
    ),
    ExecutionRules(
        titleRes = R.string.ui_settings_execution_rules,
        summaryRes = R.string.ui_settings_execution_rules_summary,
        routeSuffix = "execution-rules",
    ),
    GeneralSettings(
        titleRes = R.string.ui_settings_general,
        summaryRes = R.string.ui_settings_general_summary,
        routeSuffix = "general-settings",
    ),
    About(
        titleRes = R.string.ui_settings_about,
        summaryRes = R.string.ui_settings_about_summary,
        routeSuffix = "about",
    ),
}
