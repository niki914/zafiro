package com.niki914.zafiro.repo

import com.niki914.zafiro.mod.LocalSettings
import com.niki914.zafiro.settings.model.RuntimeCustomTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode

internal object LocalSettingsDefaults {
    const val DEFAULT_SYSTEM_PROMPT = ""

    val defaultMemories = listOf(
        "Zafiro 的 settings 根目录是 /data/user/0/com.niki914.zafiro/files/settings/。",
        "Zafiro 自己的包名是 com.niki914.zafiro。GitHub 仓库地址是 https://github.com/niki914/zafiro 。",
        "如果用户需要备份设置，可以导出 files/settings/ 目录下的 JSON 文件，并在恢复时覆盖对应文件。",
        "不要随意修改 settings 内容；如果没有明确需要，不要读取或改写这些 JSON 文件。",
    )

    private val defaultCustomTools = listOf(
        RuntimeCustomTool(
            name = "launch_wechat",
            description = "启动微信",
            command = "am start -n com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            enabled = true,
        )
    )

    val defaultExecutionRules = listOf(
        RuntimeExecutionRule(
            id = "builtin-dangerous-delete",
            name = "危险删改",
            enabledMode = RuntimeExecutionRuleEnabledMode.LOCKED_ONLY,
            patterns = listOf(
                "\\brm\\s+-rf\\b",
                "\\brm\\s+-(?=[^\\s]*r)(?=[^\\s]*f)[^\\s]*\\b",
                "\\brm\\s+-r\\s+-f\\b",
                "\\brm\\s+(?=[^\\n]*--recursive\\b)(?=[^\\n]*--force\\b)[^\\n]*",
                "\\brm\\s+(?=[^\\n]*-(?:[^\\s-]*r[^\\s-]*|-[^-\\s]*recursive)\\b)(?=[^\\n]*-(?:[^\\s-]*f[^\\s-]*|-[^-\\s]*force)\\b)[^\\n]*",
                "\\bmkfs\\b",
            ),
        ),
        RuntimeExecutionRule(
            id = "builtin-uninstall",
            name = "卸载相关",
            enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
            patterns = listOf("\\bpm\\s+uninstall\\b", "\\bcmd\\s+package\\s+uninstall\\b"),
        ),
        RuntimeExecutionRule(
            id = "builtin-privileged",
            name = "高危提权",
            enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
            patterns = listOf("\\bsu\\b", "\\bsetprop\\b", "\\bdd\\b", "\\breboot\\b"),
        ),
    )

    fun applyTo(settings: LocalSettings): LocalSettings {
        return LocalSettingsCodec.withExecutionRules(
            settings = LocalSettingsCodec.withCustomTools(
                settings = LocalSettingsCodec.withMemories(
                    settings = LocalSettingsCodec.withPrompt(
                        settings = settings,
                        prompt = DEFAULT_SYSTEM_PROMPT.trimIndent(),
                    ),
                    memories = defaultMemories,
                ),
                tools = defaultCustomTools,
            ),
            rules = defaultExecutionRules,
        )
    }
}
