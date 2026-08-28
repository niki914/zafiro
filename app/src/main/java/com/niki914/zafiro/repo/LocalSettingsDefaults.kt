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
}
