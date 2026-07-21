package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.array
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object MemorySettingsCodec {
    fun parseMemories(json: String): List<String> {
        val memories = parseObject(json).array(MEMORIES_KEY) ?: return emptyList()
        return memories.mapNotNull { element ->
            val content = when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string(CONTENT_KEY)
                else -> null
            }?.trim()
            content?.takeIf(String::isNotBlank)
        }
    }

    fun encodeMemories(memories: List<String>, nowMillis: Long): String {
        val entries = memories
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapIndexed { index, content ->
                JsonObject(
                    mapOf(
                        ID_KEY to JsonPrimitive("mem_${nowMillis}_$index"),
                        CONTENT_KEY to JsonPrimitive(content),
                        CREATED_AT_KEY to JsonPrimitive(nowMillis),
                        UPDATED_AT_KEY to JsonPrimitive(nowMillis),
                    )
                )
            }
        return JsonObject(mapOf(MEMORIES_KEY to JsonArray(entries))).toString()
    }

    private const val MEMORIES_KEY = "memories"
    private const val ID_KEY = "id"
    private const val CONTENT_KEY = "content"
    private const val CREATED_AT_KEY = "created_at"
    private const val UPDATED_AT_KEY = "updated_at"
}
