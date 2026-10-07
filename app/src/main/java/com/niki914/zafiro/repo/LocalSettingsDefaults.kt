package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.zafiro.app.R
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode

internal object LocalSettingsDefaults {
    const val DEFAULT_SYSTEM_PROMPT = ""

    /** 内置安全规则 id；onboarding 配置页保存时复用同一 id，两条默认互相覆盖。 */
    const val DEFAULT_EXECUTION_RULE_ID = "builtin-dangerous"

    /** 内置规则与 onboarding 配置页的默认规则共用同一显示名。 */
    const val DEFAULT_EXECUTION_RULE_NAME = "default"

    // Seed memories live in res/raw/seed_memories.txt，一行一条（与 seed_py_*.py 同一模式）。
    fun defaultMemories(context: Context): List<String> {
        val text = context.resources.openRawResource(R.raw.seed_memories)
            .bufferedReader().use { it.readText() }
        return text.lines().map(String::trim).filter(String::isNotEmpty)
    }

    val defaultExecutionRules = listOf(
        RuntimeExecutionRule(
            id = DEFAULT_EXECUTION_RULE_ID,
            name = DEFAULT_EXECUTION_RULE_NAME,
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
