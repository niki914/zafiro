package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.array
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.boolean
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.string
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.stringArray
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverSettings as TakeoverSettings
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget as TakeoverTarget

internal object RuleSettingsCodec {
    fun parseExecutionRules(json: String): List<ExecutionRule> {
        return parseObject(json)
            .array(RULES_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id = obj.string(ID_KEY).trim()
                val name = obj.string(NAME_KEY).trim()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                ExecutionRule(
                    id = id,
                    name = name,
                    enabledMode = ExecutionRuleEnabledMode.entries.firstOrNull {
                        it.name == obj.string(ENABLED_MODE_KEY)
                    } ?: ExecutionRuleEnabledMode.DISABLED,
                    patterns = obj.array(PATTERNS_KEY).stringValues(),
                )
            }
    }

    fun encodeExecutionRules(rules: List<ExecutionRule>): String {
        return JsonObject(
            mapOf(
                RULES_KEY to JsonArray(
                    rules.map { rule ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(rule.id),
                                NAME_KEY to JsonPrimitive(rule.name),
                                ENABLED_MODE_KEY to JsonPrimitive(rule.enabledMode.name),
                                PATTERNS_KEY to stringArray(rule.patterns),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    fun parseTakeoverRules(json: String): List<TakeoverRule> {
        return parseTakeoverSettings(json).rules
    }

    fun encodeTakeoverRules(rules: List<TakeoverRule>): String {
        return JsonObject(
            mapOf(
                RULES_KEY to JsonArray(
                    rules.map { rule ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(rule.id),
                                NAME_KEY to JsonPrimitive(rule.name),
                                TARGET_KEY to JsonPrimitive(rule.target.name),
                                ENABLED_KEY to JsonPrimitive(rule.enabled),
                                PATTERNS_KEY to stringArray(rule.patterns),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    fun parseTakeoverSettings(json: String): TakeoverSettings {
        val obj = parseObject(json)
        val defaultTarget = TakeoverTarget.entries.firstOrNull {
            it.name == obj.string(DEFAULT_TARGET_KEY)
        } ?: TakeoverTarget.NEXUS
        val rules = obj.array(RULES_KEY)
            .orEmptyObjects()
            .mapNotNull { ruleObj ->
                val id = ruleObj.string(ID_KEY).trim()
                val name = ruleObj.string(NAME_KEY).trim()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                TakeoverRule(
                    id = id,
                    name = name,
                    target = TakeoverTarget.entries.firstOrNull {
                        it.name == ruleObj.string(
                            TARGET_KEY
                        )
                    }
                        ?: TakeoverTarget.NEXUS,
                    enabled = ruleObj.boolean(ENABLED_KEY, default = true),
                    patterns = ruleObj.array(PATTERNS_KEY).stringValues(),
                )
            }
        return TakeoverSettings(
            defaultTarget = defaultTarget,
            rules = rules,
        )
    }

    fun encodeTakeoverSettings(settings: TakeoverSettings): String {
        return JsonObject(
            mapOf(
                DEFAULT_TARGET_KEY to JsonPrimitive(settings.defaultTarget.name),
                RULES_KEY to JsonArray(
                    settings.rules.map { rule ->
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(rule.id),
                                NAME_KEY to JsonPrimitive(rule.name),
                                TARGET_KEY to JsonPrimitive(rule.target.name),
                                ENABLED_KEY to JsonPrimitive(rule.enabled),
                                PATTERNS_KEY to stringArray(rule.patterns),
                            )
                        )
                    }
                ),
            )
        ).toString()
    }

    private const val RULES_KEY = "rules"
    private const val DEFAULT_TARGET_KEY = "default_target"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val ENABLED_MODE_KEY = "enabled_mode"
    private const val TARGET_KEY = "target"
    private const val ENABLED_KEY = "enabled"
    private const val PATTERNS_KEY = "patterns"
}
