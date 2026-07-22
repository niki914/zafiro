package com.niki914.nexus.agentic.app.ui.nexus.model

import com.niki914.nexus.xposed.api.util.OsFamily

sealed interface StartupAssistantUi {

    data object Breeno : StartupAssistantUi
    data object XiaoAi : StartupAssistantUi
    data object ChatOnly : StartupAssistantUi

    companion object {
        fun fromOsFamily(osFamily: OsFamily): StartupAssistantUi {
            return when (osFamily) {
                OsFamily.ColorOS -> Breeno
                OsFamily.HyperOS -> XiaoAi
                OsFamily.Unknown -> ChatOnly
            }
        }
    }
}
