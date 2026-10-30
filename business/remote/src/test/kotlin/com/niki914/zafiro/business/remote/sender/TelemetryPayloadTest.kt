package com.niki914.zafiro.business.remote.sender

import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.business.remote.ledger.DayPage
import com.niki914.zafiro.business.remote.ledger.InstallFacts
import com.niki914.zafiro.business.remote.ledger.SendSnapshot
import com.niki914.zafiro.business.remote.profile.DeviceProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TelemetryPayloadTest {

    @Test
    fun `payload build preserves required schema structure with nullable fields`() {
        val today = LocalDate.of(2026, 10, 30)
        val snapshot = SendSnapshot(
            days = listOf(
                DayPage(
                    day = today,
                    dayKey = "6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11",
                    rev = 1,
                    ackedRev = 0,
                    versionCode = 16,
                    prevActiveDay = null,
                    prevVersionCode = null,
                    counts = emptyList(),
                    countsTruncated = 0,
                    firsts = emptyList(),
                    custom = emptyMap(),
                )
            ),
            droppedDays = 0,
            envelopeCustom = emptyMap(),
        )

        val installFacts = InstallFacts(
            v = 1,
            installStampMs = 12345L,
            firstChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            firstVersionCode = null,
            installDay = "2026-10-25",
        )

        val profile = DeviceProfile(
            apiLevel = 34,
            lang = "zh-CN",
            brand = "Xiaomi",
            model = "23127PN0CC",
            abi = "arm64-v8a",
            ramGb = 12,
            screenClass = "sw400_xxhdpi",
            localeRegion = "CN",
            tzId = "Asia/Shanghai",
            tzOffsetMin = 480,
        )

        val jsonString = TelemetryPayload.build(
            snapshot = snapshot,
            today = today,
            configVersion = null,
            versionCode = 16,
            currentChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            buildType = "release",
            appLang = null,
            installFacts = installFacts,
            installer = null,
            profile = profile,
        )

        val root = JSONObject(jsonString)
        assertEquals(TelemetryContract.SCHEMA_VERSION, root.getInt("schema_version"))
        assertEquals(TelemetryContract.CONTRACT_VERSION, root.getInt("contract_version"))
        assertTrue(root.isNull("config_version"))
        assertEquals("2026-10-30", root.getString("client_day"))

        val app = root.getJSONObject("app")
        assertEquals(16, app.getInt("version_code"))
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, app.getString("current_channel"))
        assertEquals("release", app.getString("build_type"))
        assertTrue(app.isNull("app_lang"))

        val install = root.getJSONObject("install")
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, install.getString("first_channel"))
        assertTrue(install.isNull("first_version_code"))
        assertEquals("2026-10-25", install.getString("install_day"))
        assertTrue(install.isNull("installer"))

        val device = root.getJSONObject("device")
        assertEquals(34, device.getInt("api_level"))
        assertEquals("zh-CN", device.getString("lang"))
        assertEquals("Xiaomi", device.getString("brand"))
        assertEquals("23127PN0CC", device.getString("model"))
        assertEquals("arm64-v8a", device.getString("abi"))
        assertEquals(12, device.getInt("ram_gb"))
        assertEquals("sw400_xxhdpi", device.getString("screen_class"))
        assertEquals("CN", device.getString("locale_region"))
        assertEquals("Asia/Shanghai", device.getString("tz_id"))
        assertEquals(480, device.getInt("tz_offset_min"))

        val days = root.getJSONArray("days")
        assertEquals(1, days.length())
        val day0 = days.getJSONObject(0)
        assertEquals("6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11", day0.getString("day_key"))
        assertEquals("2026-10-30", day0.getString("activity_day"))
        assertEquals(16, day0.getInt("version_code"))
        assertEquals(1, day0.getInt("rev"))
        assertTrue(day0.isNull("prev_active_day"))
        assertTrue(day0.isNull("prev_version_code"))
    }
}
