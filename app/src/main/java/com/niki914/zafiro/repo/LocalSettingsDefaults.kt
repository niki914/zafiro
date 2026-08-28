package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.zafiro.app.R
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode

internal object LocalSettingsDefaults {
    const val DEFAULT_SYSTEM_PROMPT = ""

    // Seed memories live in res/raw/seed_memories.txt，一行一条（与 seed_py_*.py 同一模式）。
    fun defaultMemories(context: Context): List<String> {
        val text = context.resources.openRawResource(R.raw.seed_memories)
            .bufferedReader().use { it.readText() }
        return text.lines().map(String::trim).filter(String::isNotEmpty)
    }

    val defaultExecutionRules = listOf(
        RuntimeExecutionRule(
            id = "builtin-dangerous",
            name = "高危操作",
            enabledMode = RuntimeExecutionRuleEnabledMode.CONFIRM,
            patterns = listOf(
                // 危险删改
                "\\brm\\s+-rf\\b",
                "\\brm\\s+-(?=[^\\s]*r)(?=[^\\s]*f)[^\\s]*\\b",
                "\\brm\\s+-r\\s+-f\\b",
                "\\brm\\s+(?=[^\\n]*--recursive\\b)(?=[^\\n]*--force\\b)[^\\n]*",
                "\\brm\\s+(?=[^\\n]*-(?:[^\\s-]*r[^\\s-]*|-[^-\\s]*recursive)\\b)(?=[^\\n]*-(?:[^\\s-]*f[^\\s-]*|-[^-\\s]*force)\\b)[^\\n]*",
                "\\bmkfs\\b",
                // 卸载相关
                "\\bpm\\s+uninstall\\b",
                "\\bcmd\\s+package\\s+uninstall\\b",
                // 高危提权
                "\\bsu\\b",
                "\\bsetprop\\b",
                "\\bdd\\b",
                "\\breboot\\b",
            ),
        ),
    )
}
