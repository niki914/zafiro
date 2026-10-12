package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object TextActionSettingsCodec {

    fun parseActions(json: String): List<RuntimeTextAction> {
        return parseObject(json)
            .array(ACTIONS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name = obj.string(NAME_KEY).trim()
                val prompt = obj.string(PROMPT_KEY).trim()
                // name / prompt 是动作的最小可用条件，缺一跳过（与 McpSettingsCodec 同风格）
                if (name.isBlank() || prompt.isBlank()) return@mapNotNull null
                RuntimeTextAction(
                    name = name,
                    prompt = prompt,
                    enabled = obj.boolean(ENABLED_KEY, default = true),
                    requiresScreenshot = obj.boolean(SCREENSHOT_KEY, default = false),
                    runInBackground = obj.boolean(BACKGROUND_KEY, default = false),
                )
            }
    }

    fun encodeActions(actions: List<RuntimeTextAction>): String {
        return JsonObject(
            mapOf(
                ACTIONS_KEY to JsonArray(
                    actions.map { action ->
                        JsonObject(
                            mapOf(
                                NAME_KEY to JsonPrimitive(action.name),
                                PROMPT_KEY to JsonPrimitive(action.prompt),
                                ENABLED_KEY to JsonPrimitive(action.enabled),
                                SCREENSHOT_KEY to JsonPrimitive(action.requiresScreenshot),
                                BACKGROUND_KEY to JsonPrimitive(action.runInBackground),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    private const val ACTIONS_KEY = "actions"
    private const val NAME_KEY = "name"
    private const val PROMPT_KEY = "prompt"
    private const val ENABLED_KEY = "enabled"
    private const val SCREENSHOT_KEY = "requires_screenshot"
    private const val BACKGROUND_KEY = "run_in_background"
}
