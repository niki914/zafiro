package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.xposed.api.util.OsFamily

sealed interface StartupAssistantUi {
    @get:StringRes
    val statusTextRes: Int

    @get:StringRes
    val buttonTextRes: Int

    data object Breeno : StartupAssistantUi {
        override val statusTextRes: Int = R.string.ui_onboard_startup_status_breeno
        override val buttonTextRes: Int = R.string.ui_onboard_startup_continue
    }

    data object XiaoAi : StartupAssistantUi {
        override val statusTextRes: Int = R.string.ui_onboard_startup_status_xiaoai
        override val buttonTextRes: Int = R.string.ui_onboard_startup_continue
    }

    data object ChatOnly : StartupAssistantUi {
        override val statusTextRes: Int = R.string.ui_onboard_startup_status_chat_only
        override val buttonTextRes: Int = R.string.ui_onboard_startup_continue_chat_only
    }

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
