package com.niki914.zafiro.business.remote.profile

import com.niki914.xsettings.XSettings
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.service.requireService

/**
 * `app.app_lang`。空串表示跟随系统，上报 null；超过契约长度也上报 null。
 */
internal object ReportedAppLanguage {
    private val settings: XSettings
        get() = requireService()

    fun read(): String? = boundedLanguageTag(
        settings.appLanguageTag.value,
        TelemetryContract.LIMIT_APP_LANG_LENGTH,
    )
}
