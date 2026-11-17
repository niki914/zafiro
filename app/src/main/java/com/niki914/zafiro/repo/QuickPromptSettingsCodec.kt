package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringNonBlankOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object QuickPromptSettingsCodec {

    fun parsePrompts(json: String): List<RuntimeQuickPrompt> {
        return parseObject(json)
            .array(PROMPTS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name = obj.stringNonBlankOrNull(NAME_KEY) ?: return@mapNotNull null
                val prompt = obj.stringNonBlankOrNull(PROMPT_KEY) ?: return@mapNotNull null
                RuntimeQuickPrompt(
                    name = name,
                    prompt = prompt,
                    enabled = obj.boolean(ENABLED_KEY, default = true),
                )
            }
    }

    fun encodePrompts(prompts: List<RuntimeQuickPrompt>): String {
        return JsonObject(
            mapOf(
                PROMPTS_KEY to JsonArray(
                    prompts.map { item ->
                        JsonObject(
                            mapOf(
                                NAME_KEY to JsonPrimitive(item.name),
                                PROMPT_KEY to JsonPrimitive(item.prompt),
                                ENABLED_KEY to JsonPrimitive(item.enabled),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    private const val PROMPTS_KEY = "prompts"
    private const val NAME_KEY = "name"
    private const val PROMPT_KEY = "prompt"
    private const val ENABLED_KEY = "enabled"
}
