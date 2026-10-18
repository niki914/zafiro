package com.niki914.zafiro.repo

import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SkillEnabledStateStore(
    private val statusFile: File,
) {
    fun readStates(): Map<String, Boolean> {
        return tryOrNull {
            if (!statusFile.isFile) {
                return@tryOrNull emptyMap()
            }
            val root = SettingsJsonCodecUtils.parseObject(statusFile.readText(Charsets.UTF_8))
            val enabled = with(SettingsJsonCodecUtils) { root.obj("enabled") } ?: return@tryOrNull emptyMap()
            enabled
                .mapNotNull { (id, value) ->
                    val enabledValue = tryOrNull { value.jsonPrimitive.booleanOrNull }
                        ?: return@mapNotNull null
                    id to enabledValue
                }
                .toMap()
        } ?: emptyMap()
    }

    fun isEnabled(id: String): Boolean {
        return readStates()[id] ?: true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        writeStates(readStates() + (id to enabled))
    }

    fun remove(id: String) {
        writeStates(readStates() - id)
    }

    private fun writeStates(states: Map<String, Boolean>) {
        tryIgnore {
            statusFile.parentFile?.mkdirs()
            val enabled = JsonObject(
                states.toSortedMap().mapValues { JsonPrimitive(it.value) },
            )
            val root = JsonObject(mapOf("enabled" to enabled))
            statusFile.writeText(root.toString(), Charsets.UTF_8)
        }
    }
}
