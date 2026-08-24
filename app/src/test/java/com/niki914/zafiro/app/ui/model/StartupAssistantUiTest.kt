package com.niki914.zafiro.app.ui.model

import com.niki914.xposed.api.util.OsFamily
import com.niki914.zafiro.app.ui.model.StartupAssistantUi
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupAssistantUiTest {

    @Test
    fun fromOsFamily_mapsColorOsToBreeno() {
        val result = StartupAssistantUi.fromOsFamily(OsFamily.ColorOS)

        assertEquals(StartupAssistantUi.Breeno, result)
    }

    @Test
    fun fromOsFamily_mapsHyperOsToXiaoAi() {
        val result = StartupAssistantUi.fromOsFamily(OsFamily.HyperOS)

        assertEquals(StartupAssistantUi.XiaoAi, result)
    }

    @Test
    fun fromOsFamily_mapsUnknownToChatOnly() {
        val result = StartupAssistantUi.fromOsFamily(OsFamily.Unknown)

        assertEquals(StartupAssistantUi.ChatOnly, result)
    }
}
