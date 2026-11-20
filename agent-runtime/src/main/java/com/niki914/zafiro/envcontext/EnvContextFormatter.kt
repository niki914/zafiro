package com.niki914.zafiro.envcontext

import com.niki914.zafiro.api.screen.VirtualDisplayContext
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object EnvContextFormatter {

    private val PLACEHOLDER_REGEX = Regex("\\{\\{([a-zA-Z0-9_-]+)\\}\\}")

    fun formatVirtualDisplay(context: VirtualDisplayContext?): String? {
        if (context == null) return null
        val screenId = context.screenId.value.trim()
        if (screenId.isEmpty()) return null
        val packageName = context.packageName?.trim().orEmpty()
        val appName = context.appName?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return "Virtual Display: $screenId, No App Launched"
        }
        val appLabel = if (appName.isNotEmpty() && appName != packageName) {
            "$appName ($packageName)"
        } else {
            packageName
        }
        return "Virtual Display: $screenId, Last App: $appLabel"
    }

    fun formatContextBlock(
        timestamp: Long,
        deviceState: DeviceStateSnapshot?,
        config: RuntimeEnvContextConfig,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        virtualDisplayText: String? = null,
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
        if (config.keys.contains(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY)) {
            val formattedVirtualDisplay = virtualDisplayText?.trim().orEmpty()
            if (formattedVirtualDisplay.isNotEmpty()) {
                parts += formattedVirtualDisplay
            }
        }

        // 2. Custom variables (sorted by key for deterministic output)
        val customKeys = config.keys.filter {
            !isBuiltinKey(it) &&
                config.variables.containsKey(it)
        }.sorted()

        for (key in customKeys) {
            val value = config.variables[key].orEmpty()
            parts += "$key: $value"
        }

        // 3. Unknown keys (neither built-in nor in custom variables)
        val unknownKeys = config.keys.filter {
            !isBuiltinKey(it) &&
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
        virtualDisplayText: String? = null,
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
                key.equals(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY, ignoreCase = true) ->
                    virtualDisplayText.orEmpty()
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
        virtualDisplayText: String? = null,
    ): String {
        if (!config.enabled) return text

        val replaced = replacePlaceholders(
            text = text,
            timestamp = timestamp,
            deviceState = deviceState,
            config = config,
            zoneId = zoneId,
            locale = locale,
            virtualDisplayText = virtualDisplayText,
        )
        if (replaced.contains("<zfr-context>")) {
            return replaced
        }

        val block = formatContextBlock(
            timestamp = timestamp,
            deviceState = deviceState,
            config = config,
            zoneId = zoneId,
            locale = locale,
            virtualDisplayText = virtualDisplayText,
        ) ?: return replaced
        return if (replaced.isBlank()) {
            block
        } else {
            "${replaced.trimEnd()}\n\n$block"
        }
    }

    private fun isBuiltinKey(key: String): Boolean {
        return key == RuntimeEnvContextConfig.KEY_DATE ||
            key == RuntimeEnvContextConfig.KEY_TIME ||
            key == RuntimeEnvContextConfig.KEY_DEVICE ||
            key == RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY
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
