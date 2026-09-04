package com.niki914.zafiro.repo

import com.niki914.zafiro.mod.WebSettings
import kotlinx.serialization.json.JsonObject

// TODO WebSettings abstraction: future use for preferences, remote executable code, i18n, etc.
//      Current implementation is intentionally empty — config is loaded locally from
//      res/raw/legacy_xposed_hooks/ and injected via BaseConfigProvider.config.
sealed interface WebSettingsResult {
    data class Success(
        val settings: WebSettings,
        val requestedVersionCode: Long?,
        val resolvedVersionCode: Long?,
        val source: WebSettingsSource,
        val isFallbackVersion: Boolean,
    ) : WebSettingsResult

    data class RequestFailed(
        val reason: WebSettingsFailureReason,
        val cause: Throwable? = null,
    ) : WebSettingsResult

    data object IpcUnreachable : WebSettingsResult

    fun configOrNull(): JsonObject? = (this as? Success)?.settings?.config
}

// TODO placeholder — define when reimplementing web settings
enum class WebSettingsSource

// TODO placeholder — define when reimplementing web settings
enum class WebSettingsFailureReason

// TODO placeholder — define when reimplementing web settings
object WebSettingsVersionFallback
