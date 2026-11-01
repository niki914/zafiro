// 保护：应用内语言空串或超长时上报成一段语言，跟随系统和服务端长度上限就失效
package com.niki914.zafiro.business.remote.profile

import com.niki914.xsettings.XSettings
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportedAppLanguageTest {
    @AfterTest
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun `a normal tag is read from the settings flow`() {
        install("ja-JP")
        assertEquals("ja-JP", ReportedAppLanguage.read())
    }

    @Test
    fun `an empty tag means follow the system`() {
        install("")
        assertNull(ReportedAppLanguage.read())
    }

    @Test
    fun `a tag at the contract length is kept and one past it is dropped`() {
        val limit = TelemetryContract.LIMIT_APP_LANG_LENGTH
        install("a".repeat(limit))
        assertEquals("a".repeat(limit), ReportedAppLanguage.read())
        install("a".repeat(limit + 1))
        assertNull(ReportedAppLanguage.read())
    }

    private fun install(tag: String) {
        installService<XSettings>(FakeXSettings(tag))
    }

    private class FakeXSettings(tag: String) : XSettings {
        override val floatingBallAutoExpand: StateFlow<Boolean> = MutableStateFlow(true)
        override val appLanguageTag: StateFlow<String> = MutableStateFlow(tag)
    }
}
