package com.niki914.zafiro.api.telemetry

/**
 * 埋点契约常量，与 `telemetry-contract/contract.yaml` 同一份数值。
 * 每一项都是字面量，供核对脚本按行读取。
 */
object TelemetryContract {
    const val SCHEMA_VERSION = 1
    const val CONTRACT_VERSION = 1

    const val LIMIT_BODY_BYTES = 32768
    const val LIMIT_DAYS_MIN = 1
    const val LIMIT_DAYS_MAX = 8
    const val LIMIT_COUNTS_PER_DAY = 64
    const val LIMIT_FIRSTS_PER_DAY = 64
    const val LIMIT_UNPLANNED_EVENTS_PER_DAY = 8
    const val LIMIT_NAME_LENGTH = 40
    const val LIMIT_COUNT_N_MIN = 1
    const val LIMIT_COUNT_N_MAX = 1000000
    const val LIMIT_CUSTOM_KEYS = 16
    const val LIMIT_CUSTOM_UNDECLARED_KEYS = 8
    const val LIMIT_CUSTOM_STRING_LENGTH = 40
    const val LIMIT_BRAND_LENGTH = 64
    const val LIMIT_MODEL_LENGTH = 64
    const val LIMIT_INSTALLER_LENGTH = 100
    const val LIMIT_TZ_OFFSET_MIN = -720
    const val LIMIT_TZ_OFFSET_MAX = 840
    const val LIMIT_METRIC_MIN = 0
    const val LIMIT_METRIC_MAX = 2147483647
    const val LIMIT_CHANNEL_NAME_MIN = 1
    const val LIMIT_CHANNEL_NAME_MAX = 32
    const val LIMIT_CHANNEL_NAME_PATTERN = "[a-z0-9_]{1,32}"
    const val LIMIT_LANG_LENGTH = 16
    const val LIMIT_APP_LANG_LENGTH = 16
    const val LIMIT_LOCALE_REGION_LENGTH = 2
    const val LIMIT_DAY_KEY_LENGTH = 36
    const val LIMIT_ACTIVITY_WINDOW_DAYS = 8
    const val LIMIT_CLIENT_RETENTION_PAST_DAYS = 7
    const val LIMIT_CLOCK_ROLLBACK_DAYS = 8
    const val LIMIT_DELETED_DAY_MEMORY_DAYS = 9
    const val LIMIT_REV_MIN = 1
    const val LIMIT_DROPPED_DAYS_MIN = 0
    const val LIMIT_COUNTS_TRUNCATED_MIN = 0
    const val LIMIT_VERSION_CODE_MIN = 1

    const val CHANNEL_GITHUB_NAME = "github"
    const val CHANNEL_GITHUB_KIND = "official"
    const val CHANNEL_XPOSED_NAME = "xposed"
    const val CHANNEL_XPOSED_KIND = "repo"
    const val CHANNEL_UNKNOWN_NAME = "unknown"
    const val CHANNEL_UNKNOWN_KIND = "unknown"
    const val CHANNEL_DEV_NAME = "dev"
    const val CHANNEL_DEV_KIND = "dev"
    const val CHANNEL_LEGACY_NAME = "legacy"
    const val CHANNEL_LEGACY_KIND = "legacy"

    const val CONFIG_KEY_TELEMETRY_ENABLED_TYPE = "bool"
    const val CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT = true
    const val CONFIG_KEY_REPORT_INTERVAL_HOURS_TYPE = "int"
    const val CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT = 24
    const val CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN = 24
    const val CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX = 168
    const val CONFIG_KEY_CONFIG_TTL_HOURS_TYPE = "int"
    const val CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT = 12
    const val CONFIG_KEY_CONFIG_TTL_HOURS_MIN = 12
    const val CONFIG_KEY_CONFIG_TTL_HOURS_MAX = 168

    const val RAM_GB_BUCKETS = "2,3,4,6,8,12,16,24"
    const val SCREEN_WIDTH_BUCKETS = "320,360,400,480,600,720"
    const val SCREEN_DENSITY_BUCKETS = "mdpi:160,hdpi:240,xhdpi:320,xxhdpi:480,xxxhdpi:640"
    const val BUILD_TYPES = "release,debug"
    const val PROFILE_FIELDS = "api_level,brand,model,abi,ram_gb,screen_class,lang,app_lang,locale_region,tz_id,tz_offset_min,installer"
    const val EVENT_COUNT = 0
    const val CUSTOM_KEY_COUNT = 0
}
