package com.niki914.zafiro.envcontext

import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object EnvContextFormatter {

    private val PLACEHOLDER_REGEX = Regex("\\{\\{([a-zA-Z0-9_-]+)\\}\\}")

    fun formatContextBlock(
        timestamp: Long,
        deviceState: DeviceStateSnapshot?,
        config: RuntimeEnvContextConfig,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (!config.enabled || config.keys.isEmpty()) return null

        val parts = mutableListOf<String>()

        // 1. Built-in keys in canonical order
        if (config.keys.contains(RuntimeEnvContextConfig.KEY_DATE)) {
            parts += formatDate(timestamp, zoneId, locale)
        }
        if (config.keys.contains(RuntimeEnvContextConfig.KEY_TIME)) {
            parts += formatTime(timestamp, zoneId, locale)
        }
        if (config.keys.contains(RuntimeEnvContextConfig.KEY_DEVICE)) {
            val formattedDevice = deviceState?.format().orEmpty()
            if (formattedDevice.isNotBlank()) {
                parts += formattedDevice
            }
        }

        // 2. Custom variables (sorted by key for deterministic output)
        val customKeys = config.keys.filter {
            it != RuntimeEnvContextConfig.KEY_DATE &&
                it != RuntimeEnvContextConfig.KEY_TIME &&
                it != RuntimeEnvContextConfig.KEY_DEVICE &&
                config.variables.containsKey(it)
        }.sorted()

        for (key in customKeys) {
            val value = config.variables[key].orEmpty()
            parts += "$key: $value"
        }

        // 3. Unknown keys (neither built-in nor in custom variables)
        val unknownKeys = config.keys.filter {
            it != RuntimeEnvContextConfig.KEY_DATE &&
                it != RuntimeEnvContextConfig.KEY_TIME &&
                it != RuntimeEnvContextConfig.KEY_DEVICE &&
                !config.variables.containsKey(it)
        }.sorted()

        if (unknownKeys.isNotEmpty()) {
            parts += "Unknown Keys: ${unknownKeys.joinToString(", ")}"
        }

        if (parts.isEmpty()) return null

        return "<zfr-context>\n    ${parts.joinToString(" | ")}\n</zfr-context>"
    }

    fun replacePlaceholders(
        text: String,
        timestamp: Long,
        deviceState: DeviceStateSnapshot?,
        config: RuntimeEnvContextConfig,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (!text.contains("{{")) return text

        return PLACEHOLDER_REGEX.replace(text) { matchResult ->
            val key = matchResult.groupValues[1]
            when {
                key.equals(RuntimeEnvContextConfig.KEY_DATE, ignoreCase = true) ->
                    formatDate(timestamp, zoneId, locale)
                key.equals(RuntimeEnvContextConfig.KEY_TIME, ignoreCase = true) ->
                    formatTime(timestamp, zoneId, locale)
                key.equals(RuntimeEnvContextConfig.KEY_DEVICE, ignoreCase = true) ->
                    deviceState?.format().orEmpty()
                config.variables.containsKey(key) ->
                    config.variables[key].orEmpty()
                else -> matchResult.value
            }
        }
    }

    fun injectIntoText(
        text: String,
        timestamp: Long,
        deviceState: DeviceStateSnapshot?,
        config: RuntimeEnvContextConfig,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (!config.enabled) return text

        val replaced = replacePlaceholders(text, timestamp, deviceState, config, zoneId, locale)
        if (replaced.contains("<zfr-context>")) {
            return replaced
        }

        val block = formatContextBlock(timestamp, deviceState, config, zoneId, locale) ?: return replaced
        return if (replaced.isBlank()) {
            block
        } else {
            "${replaced.trimEnd()}\n\n$block"
        }
    }

    private fun formatDate(timestamp: Long, zoneId: ZoneId, locale: Locale): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE", locale)
        return instant.atZone(zoneId).format(formatter)
    }

    private fun formatTime(timestamp: Long, zoneId: ZoneId, locale: Locale): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        return instant.atZone(zoneId).format(formatter)
    }
}
