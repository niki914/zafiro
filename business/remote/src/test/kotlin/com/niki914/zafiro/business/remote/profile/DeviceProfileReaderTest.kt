// 保护：内存、屏宽、密度分档或语言截断读错时，画像会落进错误的档
package com.niki914.zafiro.business.remote.profile

import com.niki914.zafiro.api.telemetry.TelemetryContract
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceProfileReaderTest {
    @Test
    fun `ram rounds up through 1024 byte gibibytes and caps at the top bucket`() {
        val gib = 1024L * 1024L * 1024L
        val cases = mapOf(
            0L to 2,
            2 * gib to 2,
            2 * gib + 1 to 3,
            gib * 74 / 10 to 8,
            8 * gib to 8,
            8 * gib + 1 to 12,
            6_050_000_000L to 6,
            24 * gib to 24,
            24 * gib + 1 to 24,
        )
        val actual = cases.mapValues { (bytes, _) -> profile(totalMemBytes = bytes).ramGb }
        assertEquals(cases, actual)
    }

    @Test
    fun `screen width uses the greatest bucket that does not exceed the dp`() {
        val cases = mapOf(
            0 to 320,
            319 to 320,
            320 to 320,
            359 to 320,
            360 to 360,
            400 to 400,
            720 to 720,
            800 to 720,
        )
        val actual = cases.mapValues { (dp, _) ->
            val screen = profile(smallestScreenWidthDp = dp, densityDpi = 160).screenClass
            Regex("sw(\\d+)_mdpi").matchEntire(screen)?.groupValues?.get(1)?.toInt()
        }
        assertEquals(cases, actual)
    }

    @Test
    fun `density uses the nearest bucket and the higher bucket on a tie`() {
        val cases = mapOf(
            0 to "mdpi",
            160 to "mdpi",
            200 to "hdpi",
            320 to "xhdpi",
            400 to "xxhdpi",
            420 to "xxhdpi",
            440 to "xxhdpi",
            560 to "xxxhdpi",
            640 to "xxxhdpi",
            1000 to "xxxhdpi",
        )
        val actual = cases.mapValues { (dpi, _) ->
            profile(smallestScreenWidthDp = 320, densityDpi = dpi).screenClass.removePrefix("sw320_")
        }
        assertEquals(cases, actual)
    }

    @Test
    fun `screen class joins the width bucket and the density name`() {
        val screen = profile(smallestScreenWidthDp = 410, densityDpi = 420).screenClass
        assertEquals("sw400_xxhdpi", screen)
    }

    @Test
    fun `language keeps the bcp 47 tag including script`() {
        val lang = profile(systemLocale = Locale.forLanguageTag("zh-Hans-CN")).lang
        assertEquals("zh-Hans-CN", lang)
    }

    @Test
    fun `empty and overlong language tags are null`() {
        assertNull(profile(systemLocale = null).lang)
        assertNull(profile(systemLocale = Locale.ROOT).lang)
        val long = Locale.Builder().setLanguage("zh").setExtension('u', "ca-gregory-nu-latn").build()
        assertNull(profile(systemLocale = long).lang)
    }

    @Test
    fun `region is the uppercase two letter country`() {
        assertEquals("CN", profile(systemLocale = Locale.forLanguageTag("zh-CN")).localeRegion)
        assertEquals("US", profile(systemLocale = Locale("en", "us")).localeRegion)
    }

    @Test
    fun `missing and non two letter regions are null`() {
        assertNull(profile(systemLocale = Locale.forLanguageTag("zh")).localeRegion)
        assertNull(profile(systemLocale = Locale("en", "USA")).localeRegion)
    }

    @Test
    fun `brand and model are clipped by character length`() {
        val brand = "测".repeat(TelemetryContract.LIMIT_BRAND_LENGTH + 3)
        val model = "M".repeat(TelemetryContract.LIMIT_MODEL_LENGTH)
        val profile = profile(manufacturer = brand, model = model)
        assertEquals(brand.take(TelemetryContract.LIMIT_BRAND_LENGTH), profile.brand)
        assertEquals(model, profile.model)
    }

    @Test
    fun `timezone offset follows daylight saving at the send instant`() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val summer = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        val winter = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()
        val summerProfile = profile(timeZone = zone, nowMillis = summer)
        val winterProfile = profile(timeZone = zone, nowMillis = winter)
        assertEquals("America/Los_Angeles", summerProfile.tzId)
        assertEquals(-420, summerProfile.tzOffsetMin)
        assertEquals(-480, winterProfile.tzOffsetMin)
    }

    private fun profile(
        totalMemBytes: Long = 8L * 1024 * 1024 * 1024,
        smallestScreenWidthDp: Int = 400,
        densityDpi: Int = 440,
        systemLocale: Locale? = Locale.forLanguageTag("zh-CN"),
        manufacturer: String = "Xiaomi",
        model: String = "23127PN0CC",
        timeZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai"),
        nowMillis: Long = 0L,
    ): DeviceProfile = DeviceProfileReader.read(
        DeviceSignals(
            apiLevel = 34,
            systemLocale = systemLocale,
            manufacturer = manufacturer,
            model = model,
            abi = "arm64-v8a",
            totalMemBytes = totalMemBytes,
            smallestScreenWidthDp = smallestScreenWidthDp,
            densityDpi = densityDpi,
            timeZone = timeZone,
        ),
        nowMillis,
    )
}
