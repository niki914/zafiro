package com.niki914.zafiro.repo

import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `settings/ui_styles.json`。
 *
 * 只保存覆盖项。缺键、坏文档、非法值都不解释成某种风格，调用方继续用全局 [ThemeStyle]。
 * 不认识的键原样留在表里，避免以后多一个组件时被这次读取丢掉。
 */
internal object UiStylesCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonString: String): Map<String, String> {
        val root = try {
            json.parseToJsonElement(jsonString) as? JsonObject
        } catch (_: SerializationException) {
            return emptyMap()
        } catch (_: IllegalArgumentException) {
            return emptyMap()
        } ?: return emptyMap()
        val overrides = root["overrides"] as? JsonObject ?: return emptyMap()
        val kept = LinkedHashMap<String, String>()
        for ((key, value) in overrides) {
            val text = (value as? JsonPrimitive)?.contentOrNull ?: continue
            if (key.isEmpty() || text.isEmpty()) continue
            kept[key] = text
        }
        return kept
    }

    fun encode(overrides: Map<String, String>): String {
        val root = buildJsonObject {
            putJsonObject("overrides") {
                for ((key, value) in overrides) {
                    put(key, value)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun overrideOrNull(overrides: Map<String, String>, part: UiStylePart): ThemeStyle? {
        val raw = overrides[part.storageKey] ?: return null
        return ThemeStyle.entries.firstOrNull { it.storageKey == raw }
    }
}
