package com.niki914.zafiro.repo

// TODO WebSettings abstraction: future use for preferences, remote executable code, i18n, etc.
//      Current implementation is intentionally empty — config is loaded locally from
//      res/raw/legacy_xposed_hooks/ and injected via BaseConfigProvider.config.
class WebSettingsApi internal constructor() {
    suspend fun await(): WebSettingsResult {
        TODO("WebSettings not yet reimplemented")
    }

    suspend fun retry(): WebSettingsResult {
        TODO("WebSettings not yet reimplemented")
    }
}
