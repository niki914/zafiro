package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.obj
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import com.niki914.zafiro.settings.model.RuntimeAgentMemoryMode
import com.niki914.zafiro.settings.model.RuntimeAgentProfile
import com.niki914.store.StoreDescriptorRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object AgentSettingsCodec {
    fun parseRegistry(
        json: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<RuntimeAgentProfile> {
        val agents = parseObject(json)
            .array(AGENTS_KEY)
            .orEmptyObjects()
            .mapNotNull(::parseAgentProfile)
            .toMutableList()

        if (agents.none { it.id == StoreDescriptorRegistry.MAIN_AGENT_ID }) {
            agents.add(defaultMainProfile(nowMillis))
        }
        return agents
    }

    fun encodeRegistry(agents: List<RuntimeAgentProfile>): String {
        val entries = agents
            .sortedWith(compareBy<RuntimeAgentProfile> { it.order }.thenBy { it.createdAt })
            .map { profile ->
                JsonObject(
                    mapOf(
                        ID_KEY to JsonPrimitive(profile.id),
                        NAME_KEY to JsonPrimitive(profile.name),
                        ALIAS_KEY to JsonPrimitive(profile.alias),
                        ENABLED_KEY to JsonPrimitive(profile.enabled),
                        ORDER_KEY to JsonPrimitive(profile.order),
                        MEMORY_MODE_KEY to JsonPrimitive(encodeMemoryMode(profile.memoryMode)),
                        CREATED_AT_KEY to JsonPrimitive(profile.createdAt),
                        UPDATED_AT_KEY to JsonPrimitive(profile.updatedAt),
                    )
                )
            }
        return JsonObject(mapOf(AGENTS_KEY to JsonArray(entries))).toString()
    }

    fun normalizeAgentId(value: String): String? {
        return value.trim().lowercase().takeIf(SAFE_AGENT_ID_PATTERN::matches)
    }

    fun normalizeAlias(value: String): String? {
        return normalizeAgentId(value)
    }

    private fun parseAgentProfile(obj: JsonObject): RuntimeAgentProfile? {
        val id = normalizeAgentId(obj.string(ID_KEY)) ?: return null
        val alias = normalizeAlias(obj.string(ALIAS_KEY)) ?: return null
        return RuntimeAgentProfile(
            id = id,
            name = obj.string(NAME_KEY).trim().ifBlank { id },
            alias = alias,
            enabled = obj.boolean(ENABLED_KEY, true),
            order = obj.int(ORDER_KEY, 0),
            memoryMode = parseMemoryMode(obj.string(MEMORY_MODE_KEY)),
            createdAt = obj.long(CREATED_AT_KEY, 0L),
            updatedAt = obj.long(UPDATED_AT_KEY, 0L),
        )
    }

    private fun defaultMainProfile(nowMillis: Long): RuntimeAgentProfile {
        return RuntimeAgentProfile(
            id = StoreDescriptorRegistry.MAIN_AGENT_ID,
            name = "Main",
            alias = StoreDescriptorRegistry.MAIN_AGENT_ID,
            enabled = true,
            order = 0,
            memoryMode = RuntimeAgentMemoryMode.SharedMain,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
    }

    private fun parseMemoryMode(value: String): RuntimeAgentMemoryMode {
        return when (value.trim().lowercase()) {
            MEMORY_MODE_DISABLED -> RuntimeAgentMemoryMode.Disabled
            else -> RuntimeAgentMemoryMode.SharedMain
        }
    }

    private fun encodeMemoryMode(mode: RuntimeAgentMemoryMode): String {
        return when (mode) {
            RuntimeAgentMemoryMode.Disabled -> MEMORY_MODE_DISABLED
            RuntimeAgentMemoryMode.SharedMain -> MEMORY_MODE_SHARED_MAIN
        }
    }

    private fun JsonObject.int(key: String, default: Int): Int {
        return (this[key] as? JsonPrimitive)?.intOrNull ?: default
    }

    private fun JsonObject.long(key: String, default: Long): Long {
        return (this[key] as? JsonPrimitive)?.longOrNull ?: default
    }

    private val SAFE_AGENT_ID_PATTERN = Regex("[a-z][a-z0-9_-]{1,31}")
    private const val AGENTS_KEY = "agents"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val ALIAS_KEY = "alias"
    private const val ENABLED_KEY = "enabled"
    private const val ORDER_KEY = "order"
    private const val MEMORY_MODE_KEY = "memory_mode"
    private const val CREATED_AT_KEY = "created_at"
    private const val UPDATED_AT_KEY = "updated_at"
    private const val MEMORY_MODE_DISABLED = "disabled"
    private const val MEMORY_MODE_SHARED_MAIN = "shared_main"
}
