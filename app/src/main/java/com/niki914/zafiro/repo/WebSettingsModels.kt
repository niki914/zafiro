package com.niki914.zafiro.repo

import com.niki914.zafiro.mod.WebSettings
import kotlinx.serialization.json.JsonObject

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

enum class WebSettingsSource {
    Cache,
    Network,
}

enum class WebSettingsFailureReason {
    NetworkUnavailable,
    ServerError,
    UnsupportedVersion,
    InvalidConfig,
    IpcUnreachable,
}

object WebSettingsVersionFallback {
    fun nearestVersionCode(
        requestedVersionCode: Long,
        supportedVersionCodes: List<Long>,
    ): Long? {
        return supportedVersionCodes
            .distinct()
            .minWithOrNull(
                compareBy<Long> { kotlin.math.abs(it - requestedVersionCode) }
                    .thenBy { it }
            )
    }
}
