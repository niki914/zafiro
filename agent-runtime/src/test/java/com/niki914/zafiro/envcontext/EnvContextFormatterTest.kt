package com.niki914.zafiro.envcontext

import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class EnvContextFormatterTest {

    private val fixedZone = ZoneId.of("Asia/Shanghai")
    private val fixedLocale = Locale.CHINA
    private val fixedTimestamp = java.time.ZonedDateTime.of(2026, 11, 15, 15, 30, 0, 0, fixedZone)
        .toInstant()
        .toEpochMilli()

    private val sampleDevice = DeviceStateSnapshot(
        model = "OnePlus 12",
        batteryLevel = 85,
        networkType = "Wi-Fi",
    )

    @Test
    fun formatContextBlock_aggregatesUnknownKeys() {
        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf("date", "time", "unknown_foo", "unknown_bar"),
            variables = emptyMap(),
        )

        val block = EnvContextFormatter.formatContextBlock(
            timestamp = fixedTimestamp,
            deviceState = sampleDevice,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        assertTrue(block != null)
        assertTrue(block!!.contains("2026-11-15 星期日"))
        assertTrue(block.contains("15:30"))
        assertTrue(block.contains("Unknown Keys: unknown_bar, unknown_foo"))
    }

    @Test
    fun formatContextBlock_respectsBuiltinAndCustomKeys() {
        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf(
                RuntimeEnvContextConfig.KEY_DATE,
                RuntimeEnvContextConfig.KEY_TIME,
                RuntimeEnvContextConfig.KEY_DEVICE,
                "role",
            ),
            variables = mapOf("role" to "Developer"),
        )

        val block = EnvContextFormatter.formatContextBlock(
            timestamp = fixedTimestamp,
            deviceState = sampleDevice,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        val expected = "<zfr-context>\n" +
            "    2026-11-15 星期日 | 15:30 | OnePlus 12 · 85% · Wi-Fi | role: Developer\n" +
            "</zfr-context>"
        assertEquals(expected, block)
    }

    @Test
    fun replacePlaceholders_replacesKnownPlaceholders() {
        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = RuntimeEnvContextConfig.DEFAULT_KEYS,
            variables = mapOf("city" to "Shenzhen"),
        )

        val input = "Hello at {{time}} in {{city}}, device is {{device}}, keep {{unknown}}."
        val output = EnvContextFormatter.replacePlaceholders(
            text = input,
            timestamp = fixedTimestamp,
            deviceState = sampleDevice,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        assertEquals(
            "Hello at 15:30 in Shenzhen, device is OnePlus 12 · 85% · Wi-Fi, keep {{unknown}}.",
            output,
        )
    }

    @Test
    fun injectIntoText_appendsBlockWithoutDuplicate() {
        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf("time"),
            variables = emptyMap(),
        )

        val firstInjection = EnvContextFormatter.injectIntoText(
            text = "User message",
            timestamp = fixedTimestamp,
            deviceState = null,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        assertTrue(firstInjection.startsWith("User message\n\n<zfr-context>"))
        assertTrue(firstInjection.contains("15:30"))

        val secondInjection = EnvContextFormatter.injectIntoText(
            text = firstInjection,
            timestamp = fixedTimestamp + 60_000L,
            deviceState = null,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        assertEquals(firstInjection, secondInjection)
    }

    @Test
    fun disabledConfig_leavesTextUntouched() {
        val config = RuntimeEnvContextConfig(
            enabled = false,
            keys = setOf("time"),
        )

        val output = EnvContextFormatter.injectIntoText(
            text = "User message with {{time}}",
            timestamp = fixedTimestamp,
            deviceState = sampleDevice,
            config = config,
            zoneId = fixedZone,
            locale = fixedLocale,
        )

        assertEquals("User message with {{time}}", output)
        assertNull(
            EnvContextFormatter.formatContextBlock(
                timestamp = fixedTimestamp,
                deviceState = sampleDevice,
                config = config,
                zoneId = fixedZone,
                locale = fixedLocale,
            )
        )
    }
}
