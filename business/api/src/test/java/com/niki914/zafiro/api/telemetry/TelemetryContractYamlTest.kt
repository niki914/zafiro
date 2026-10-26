// 保护：contract.yaml 与 TelemetryContract 分叉时，两端上限、渠道或画像长度不一致，上报会被拒或按错误上限截断
package com.niki914.zafiro.api.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryContractYamlTest {

    @Test
    fun `contract yaml matches the kotlin constants`() {
        val doc = parseContractDocument(contractYamlFile().readText())
        val diffs = contractMismatches(doc, compiledTelemetryConstants())
        assertEquals(diffs.joinToString("\n"), emptyList<String>(), diffs)
    }

    @Test
    fun `a limit missing from the document is reported`() {
        val doc = parseContractDocument(contractYamlFile().readText())
        val broken = doc.copy(limits = doc.limits - "bodyBytes")
        val diffs = contractMismatches(broken, compiledTelemetryConstants())
        assertTrue(diffs.joinToString("\n"), diffs.any { "LIMIT_BODY_BYTES" in it })
    }

    @Test
    fun `an unknown limit key is reported`() {
        val doc = parseContractDocument(contractYamlFile().readText())
        val broken = doc.copy(limits = doc.limits + ("notALimit" to 1))
        val diffs = contractMismatches(broken, compiledTelemetryConstants())
        assertTrue(diffs.joinToString("\n"), diffs.any { "notALimit" in it })
    }

    @Test
    fun `a profile json path that differs is reported`() {
        val doc = parseContractDocument(contractYamlFile().readText())
        val fields = doc.profileFields.map { field ->
            if (field.name == "brand") field.copy(json = "device.brand_typo") else field
        }
        val diffs = contractMismatches(doc.copy(profileFields = fields), compiledTelemetryConstants())
        assertTrue(diffs.joinToString("\n"), diffs.any { "brand" in it && "json" in it })
    }
}

private fun compiledTelemetryConstants(): Map<String, Any> =
    SERVER_MAPPING.associate { it.kotlin to compiledConstant(it.kotlin) }

private fun compiledConstant(name: String): Any = when (name) {
    "SCHEMA_VERSION" -> TelemetryContract.SCHEMA_VERSION
    "CONTRACT_VERSION" -> TelemetryContract.CONTRACT_VERSION
    "LIMIT_BODY_BYTES" -> TelemetryContract.LIMIT_BODY_BYTES
    "LIMIT_DAYS_MIN" -> TelemetryContract.LIMIT_DAYS_MIN
    "LIMIT_DAYS_MAX" -> TelemetryContract.LIMIT_DAYS_MAX
    "LIMIT_COUNTS_PER_DAY" -> TelemetryContract.LIMIT_COUNTS_PER_DAY
    "LIMIT_FIRSTS_PER_DAY" -> TelemetryContract.LIMIT_FIRSTS_PER_DAY
    "LIMIT_UNPLANNED_EVENTS_PER_DAY" -> TelemetryContract.LIMIT_UNPLANNED_EVENTS_PER_DAY
    "LIMIT_NAME_LENGTH" -> TelemetryContract.LIMIT_NAME_LENGTH
    "LIMIT_COUNT_N_MIN" -> TelemetryContract.LIMIT_COUNT_N_MIN
    "LIMIT_COUNT_N_MAX" -> TelemetryContract.LIMIT_COUNT_N_MAX
    "LIMIT_CUSTOM_KEYS" -> TelemetryContract.LIMIT_CUSTOM_KEYS
    "LIMIT_CUSTOM_UNDECLARED_KEYS" -> TelemetryContract.LIMIT_CUSTOM_UNDECLARED_KEYS
    "LIMIT_CUSTOM_STRING_LENGTH" -> TelemetryContract.LIMIT_CUSTOM_STRING_LENGTH
    "LIMIT_BRAND_LENGTH" -> TelemetryContract.LIMIT_BRAND_LENGTH
    "LIMIT_MODEL_LENGTH" -> TelemetryContract.LIMIT_MODEL_LENGTH
    "LIMIT_INSTALLER_LENGTH" -> TelemetryContract.LIMIT_INSTALLER_LENGTH
    "LIMIT_TZ_OFFSET_MIN" -> TelemetryContract.LIMIT_TZ_OFFSET_MIN
    "LIMIT_TZ_OFFSET_MAX" -> TelemetryContract.LIMIT_TZ_OFFSET_MAX
    "LIMIT_METRIC_MIN" -> TelemetryContract.LIMIT_METRIC_MIN
    "LIMIT_METRIC_MAX" -> TelemetryContract.LIMIT_METRIC_MAX
    "LIMIT_CHANNEL_NAME_MIN" -> TelemetryContract.LIMIT_CHANNEL_NAME_MIN
    "LIMIT_CHANNEL_NAME_MAX" -> TelemetryContract.LIMIT_CHANNEL_NAME_MAX
    "LIMIT_CHANNEL_NAME_PATTERN" -> TelemetryContract.LIMIT_CHANNEL_NAME_PATTERN
    "LIMIT_LANG_LENGTH" -> TelemetryContract.LIMIT_LANG_LENGTH
    "LIMIT_APP_LANG_LENGTH" -> TelemetryContract.LIMIT_APP_LANG_LENGTH
    "LIMIT_LOCALE_REGION_LENGTH" -> TelemetryContract.LIMIT_LOCALE_REGION_LENGTH
    "LIMIT_DAY_KEY_LENGTH" -> TelemetryContract.LIMIT_DAY_KEY_LENGTH
    "LIMIT_ACTIVITY_WINDOW_DAYS" -> TelemetryContract.LIMIT_ACTIVITY_WINDOW_DAYS
    "LIMIT_CLIENT_RETENTION_PAST_DAYS" -> TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS
    "LIMIT_CLOCK_ROLLBACK_DAYS" -> TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS
    "LIMIT_DELETED_DAY_MEMORY_DAYS" -> TelemetryContract.LIMIT_DELETED_DAY_MEMORY_DAYS
    "LIMIT_REV_MIN" -> TelemetryContract.LIMIT_REV_MIN
    "LIMIT_DROPPED_DAYS_MIN" -> TelemetryContract.LIMIT_DROPPED_DAYS_MIN
    "LIMIT_COUNTS_TRUNCATED_MIN" -> TelemetryContract.LIMIT_COUNTS_TRUNCATED_MIN
    "LIMIT_VERSION_CODE_MIN" -> TelemetryContract.LIMIT_VERSION_CODE_MIN
    "CHANNEL_GITHUB_NAME" -> TelemetryContract.CHANNEL_GITHUB_NAME
    "CHANNEL_GITHUB_KIND" -> TelemetryContract.CHANNEL_GITHUB_KIND
    "CHANNEL_XPOSED_NAME" -> TelemetryContract.CHANNEL_XPOSED_NAME
    "CHANNEL_XPOSED_KIND" -> TelemetryContract.CHANNEL_XPOSED_KIND
    "CHANNEL_UNKNOWN_NAME" -> TelemetryContract.CHANNEL_UNKNOWN_NAME
    "CHANNEL_UNKNOWN_KIND" -> TelemetryContract.CHANNEL_UNKNOWN_KIND
    "CHANNEL_DEV_NAME" -> TelemetryContract.CHANNEL_DEV_NAME
    "CHANNEL_DEV_KIND" -> TelemetryContract.CHANNEL_DEV_KIND
    "CHANNEL_LEGACY_NAME" -> TelemetryContract.CHANNEL_LEGACY_NAME
    "CHANNEL_LEGACY_KIND" -> TelemetryContract.CHANNEL_LEGACY_KIND
    "CONFIG_KEY_TELEMETRY_ENABLED_TYPE" -> TelemetryContract.CONFIG_KEY_TELEMETRY_ENABLED_TYPE
    "CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT" -> TelemetryContract.CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT
    "CONFIG_KEY_REPORT_INTERVAL_HOURS_TYPE" -> TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_TYPE
    "CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT" -> TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT
    "CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN" -> TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN
    "CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX" -> TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX
    "CONFIG_KEY_CONFIG_TTL_HOURS_TYPE" -> TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_TYPE
    "CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT" -> TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT
    "CONFIG_KEY_CONFIG_TTL_HOURS_MIN" -> TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MIN
    "CONFIG_KEY_CONFIG_TTL_HOURS_MAX" -> TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MAX
    "RAM_GB_BUCKETS" -> TelemetryContract.RAM_GB_BUCKETS
    "SCREEN_WIDTH_BUCKETS" -> TelemetryContract.SCREEN_WIDTH_BUCKETS
    "SCREEN_DENSITY_BUCKETS" -> TelemetryContract.SCREEN_DENSITY_BUCKETS
    "BUILD_TYPES" -> TelemetryContract.BUILD_TYPES
    "PROFILE_FIELDS" -> TelemetryContract.PROFILE_FIELDS
    "EVENT_COUNT" -> TelemetryContract.EVENT_COUNT
    "CUSTOM_KEY_COUNT" -> TelemetryContract.CUSTOM_KEY_COUNT
    else -> error("no kotlin constant wired for $name")
}
