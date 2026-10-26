package com.niki914.zafiro.api.telemetry

internal data class NameKind(val name: String, val kind: String)

internal data class NameDpi(val name: String, val dpi: Int)

internal data class ConfigEntry(
    val name: String,
    val type: String,
    val defaultValue: Any,
    val min: Int?,
    val max: Int?,
)

internal data class ProfileEntry(
    val name: String,
    val json: String,
    val maxLength: Int?,
    val length: Int?,
)

internal data class ContractDoc(
    val schemaVersion: Int,
    val contractVersion: Int,
    val limits: Map<String, Any>,
    val channels: List<NameKind>,
    val configKeys: List<ConfigEntry>,
    val eventCount: Int,
    val customKeyCount: Int,
    val ramGbBuckets: List<Int>,
    val screenWidthBuckets: List<Int>,
    val screenDensityBuckets: List<NameDpi>,
    val buildTypes: List<String>,
    val profileFields: List<ProfileEntry>,
)

private val topKeys = setOf(
    "schema_version",
    "contract_version",
    "limits",
    "channels",
    "config_keys",
    "events",
    "custom_keys",
    "ram_gb_buckets",
    "screen_width_buckets",
    "screen_density_buckets",
    "build_types",
    "profile_fields",
)

internal fun parseContractDocument(source: String): ContractDoc {
    val entries = parseYamlMap(source)
    val keys = entries.map { it.first }.toSet()
    if (keys != topKeys) {
        error("top-level keys missing=${topKeys - keys} extra=${keys - topKeys}")
    }
    fun child(name: String): YVal = entries.first { it.first == name }.second
    return ContractDoc(
        schemaVersion = child("schema_version").int("schema_version"),
        contractVersion = child("contract_version").int("contract_version"),
        limits = child("limits").mapping("limits").associate { (key, value) ->
            key to value.limitScalar("limits.$key")
        },
        channels = child("channels").seq("channels").mapIndexed { index, item ->
            item.mapping("channels[$index]").nameKind("channels[$index]")
        },
        configKeys = child("config_keys").seq("config_keys").mapIndexed { index, item ->
            item.mapping("config_keys[$index]").configEntry("config_keys[$index]")
        },
        eventCount = child("events").seq("events").size,
        customKeyCount = child("custom_keys").seq("custom_keys").size,
        ramGbBuckets = child("ram_gb_buckets").seq("ram_gb_buckets").mapIndexed { index, item ->
            item.int("ram_gb_buckets[$index]")
        },
        screenWidthBuckets = child("screen_width_buckets").seq("screen_width_buckets").mapIndexed { index, item ->
            item.int("screen_width_buckets[$index]")
        },
        screenDensityBuckets = child("screen_density_buckets").seq("screen_density_buckets").mapIndexed { index, item ->
            item.mapping("screen_density_buckets[$index]").density("screen_density_buckets[$index]")
        },
        buildTypes = child("build_types").seq("build_types").mapIndexed { index, item ->
            item.string("build_types[$index]")
        },
        profileFields = child("profile_fields").seq("profile_fields").mapIndexed { index, item ->
            item.mapping("profile_fields[$index]").profile("profile_fields[$index]")
        },
    )
}

private data class LimitKey(val prop: String, val const: String)

private val limitKeys = listOf(
    LimitKey("bodyBytes", "LIMIT_BODY_BYTES"),
    LimitKey("daysMin", "LIMIT_DAYS_MIN"),
    LimitKey("daysMax", "LIMIT_DAYS_MAX"),
    LimitKey("countsPerDay", "LIMIT_COUNTS_PER_DAY"),
    LimitKey("firstsPerDay", "LIMIT_FIRSTS_PER_DAY"),
    LimitKey("unplannedEventsPerDay", "LIMIT_UNPLANNED_EVENTS_PER_DAY"),
    LimitKey("nameLength", "LIMIT_NAME_LENGTH"),
    LimitKey("countNMin", "LIMIT_COUNT_N_MIN"),
    LimitKey("countNMax", "LIMIT_COUNT_N_MAX"),
    LimitKey("customKeys", "LIMIT_CUSTOM_KEYS"),
    LimitKey("customUndeclaredKeys", "LIMIT_CUSTOM_UNDECLARED_KEYS"),
    LimitKey("customStringLength", "LIMIT_CUSTOM_STRING_LENGTH"),
    LimitKey("brandLength", "LIMIT_BRAND_LENGTH"),
    LimitKey("modelLength", "LIMIT_MODEL_LENGTH"),
    LimitKey("installerLength", "LIMIT_INSTALLER_LENGTH"),
    LimitKey("tzOffsetMin", "LIMIT_TZ_OFFSET_MIN"),
    LimitKey("tzOffsetMax", "LIMIT_TZ_OFFSET_MAX"),
    LimitKey("metricMin", "LIMIT_METRIC_MIN"),
    LimitKey("metricMax", "LIMIT_METRIC_MAX"),
    LimitKey("channelNameMin", "LIMIT_CHANNEL_NAME_MIN"),
    LimitKey("channelNameMax", "LIMIT_CHANNEL_NAME_MAX"),
    LimitKey("channelNamePattern", "LIMIT_CHANNEL_NAME_PATTERN"),
    LimitKey("langLength", "LIMIT_LANG_LENGTH"),
    LimitKey("appLangLength", "LIMIT_APP_LANG_LENGTH"),
    LimitKey("localeRegionLength", "LIMIT_LOCALE_REGION_LENGTH"),
    LimitKey("dayKeyLength", "LIMIT_DAY_KEY_LENGTH"),
    LimitKey("activityWindowDays", "LIMIT_ACTIVITY_WINDOW_DAYS"),
    LimitKey("clientRetentionPastDays", "LIMIT_CLIENT_RETENTION_PAST_DAYS"),
    LimitKey("clockRollbackDays", "LIMIT_CLOCK_ROLLBACK_DAYS"),
    LimitKey("deletedDayMemoryDays", "LIMIT_DELETED_DAY_MEMORY_DAYS"),
    LimitKey("revMin", "LIMIT_REV_MIN"),
    LimitKey("droppedDaysMin", "LIMIT_DROPPED_DAYS_MIN"),
    LimitKey("countsTruncatedMin", "LIMIT_COUNTS_TRUNCATED_MIN"),
    LimitKey("versionCodeMin", "LIMIT_VERSION_CODE_MIN"),
)

private data class ChannelSpec(val name: String, val nameConst: String, val kindConst: String)

private val channelSpecs = listOf(
    ChannelSpec("github", "CHANNEL_GITHUB_NAME", "CHANNEL_GITHUB_KIND"),
    ChannelSpec("xposed", "CHANNEL_XPOSED_NAME", "CHANNEL_XPOSED_KIND"),
    ChannelSpec("unknown", "CHANNEL_UNKNOWN_NAME", "CHANNEL_UNKNOWN_KIND"),
    ChannelSpec("dev", "CHANNEL_DEV_NAME", "CHANNEL_DEV_KIND"),
    ChannelSpec("legacy", "CHANNEL_LEGACY_NAME", "CHANNEL_LEGACY_KIND"),
)

private data class ConfigSpec(
    val name: String,
    val typeConst: String,
    val defaultConst: String,
    val minConst: String?,
    val maxConst: String?,
)

private val configSpecs = listOf(
    ConfigSpec(
        "telemetry_enabled",
        "CONFIG_KEY_TELEMETRY_ENABLED_TYPE",
        "CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT",
        null,
        null,
    ),
    ConfigSpec(
        "report_interval_hours",
        "CONFIG_KEY_REPORT_INTERVAL_HOURS_TYPE",
        "CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT",
        "CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN",
        "CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX",
    ),
    ConfigSpec(
        "config_ttl_hours",
        "CONFIG_KEY_CONFIG_TTL_HOURS_TYPE",
        "CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT",
        "CONFIG_KEY_CONFIG_TTL_HOURS_MIN",
        "CONFIG_KEY_CONFIG_TTL_HOURS_MAX",
    ),
)

private data class ProfileSpec(
    val name: String,
    val json: String,
    val maxLengthConst: String?,
    val lengthConst: String?,
)

private val profileSpecs = listOf(
    ProfileSpec("api_level", "device.api_level", null, null),
    ProfileSpec("brand", "device.brand", "LIMIT_BRAND_LENGTH", null),
    ProfileSpec("model", "device.model", "LIMIT_MODEL_LENGTH", null),
    ProfileSpec("abi", "device.abi", null, null),
    ProfileSpec("ram_gb", "device.ram_gb", null, null),
    ProfileSpec("screen_class", "device.screen_class", null, null),
    ProfileSpec("lang", "device.lang", "LIMIT_LANG_LENGTH", null),
    ProfileSpec("app_lang", "app.app_lang", "LIMIT_APP_LANG_LENGTH", null),
    ProfileSpec("locale_region", "device.locale_region", null, "LIMIT_LOCALE_REGION_LENGTH"),
    ProfileSpec("tz_id", "device.tz_id", null, null),
    ProfileSpec("tz_offset_min", "device.tz_offset_min", null, null),
    ProfileSpec("installer", "install.installer", "LIMIT_INSTALLER_LENGTH", null),
)

internal fun contractMismatches(doc: ContractDoc, compiled: Map<String, Any>): List<String> {
    val problems = mutableListOf<String>()
    val projected = linkedMapOf<String, Any>()
    projected["SCHEMA_VERSION"] = doc.schemaVersion
    projected["CONTRACT_VERSION"] = doc.contractVersion

    val knownLimits = limitKeys.map { it.prop }.toSet()
    for (prop in doc.limits.keys) {
        if (prop !in knownLimits) problems += "unknown limit $prop"
    }
    for (limit in limitKeys) {
        val value = doc.limits[limit.prop] ?: continue
        projected[limit.const] = value
    }

    if (doc.channels.map { it.name } != channelSpecs.map { it.name }) {
        problems += "channel order ${doc.channels.map { it.name }}"
    }
    for (channel in doc.channels) {
        if (channelSpecs.none { it.name == channel.name }) problems += "unknown channel ${channel.name}"
    }
    for (spec in channelSpecs) {
        val channel = doc.channels.find { it.name == spec.name } ?: continue
        projected[spec.nameConst] = channel.name
        projected[spec.kindConst] = channel.kind
    }

    if (doc.configKeys.map { it.name } != configSpecs.map { it.name }) {
        problems += "config key order ${doc.configKeys.map { it.name }}"
    }
    for (key in doc.configKeys) {
        if (configSpecs.none { it.name == key.name }) problems += "unknown config key ${key.name}"
    }
    for (spec in configSpecs) {
        val key = doc.configKeys.find { it.name == spec.name } ?: continue
        projected[spec.typeConst] = key.type
        projected[spec.defaultConst] = key.defaultValue
        if (spec.minConst == null) {
            if (key.min != null || key.max != null) problems += "${spec.name}: bool key has a numeric bound"
        } else {
            key.min?.let { projected[spec.minConst] = it }
            key.max?.let { projected[spec.maxConst ?: error("missing max const")] = it }
        }
    }

    projected["RAM_GB_BUCKETS"] = doc.ramGbBuckets.joinToString(",")
    projected["SCREEN_WIDTH_BUCKETS"] = doc.screenWidthBuckets.joinToString(",")
    projected["SCREEN_DENSITY_BUCKETS"] = doc.screenDensityBuckets.joinToString(",") { "${it.name}:${it.dpi}" }
    projected["BUILD_TYPES"] = doc.buildTypes.joinToString(",")
    projected["PROFILE_FIELDS"] = doc.profileFields.joinToString(",") { it.name }
    projected["EVENT_COUNT"] = doc.eventCount
    projected["CUSTOM_KEY_COUNT"] = doc.customKeyCount

    if (doc.profileFields.map { it.name } != profileSpecs.map { it.name }) {
        problems += "profile field order ${doc.profileFields.map { it.name }}"
    }
    for (spec in profileSpecs) {
        val field = doc.profileFields.find { it.name == spec.name } ?: continue
        if (field.json != spec.json) {
            problems += "profile ${spec.name} json: yaml=${field.json} expected=${spec.json}"
        }
        val expectedMax = spec.maxLengthConst?.let { compiled[it] }
        if (!sameValue(field.maxLength, expectedMax)) {
            problems += "profile ${spec.name} maxLength: yaml=${field.maxLength} expected=$expectedMax"
        }
        val expectedLength = spec.lengthConst?.let { compiled[it] }
        if (!sameValue(field.length, expectedLength)) {
            problems += "profile ${spec.name} length: yaml=${field.length} expected=$expectedLength"
        }
    }

    for ((name, value) in projected) {
        val actual = compiled[name]
        if (actual == null) {
            problems += "$name: no kotlin constant"
        } else if (!sameValue(actual, value)) {
            problems += "$name: yaml=$value kotlin=$actual"
        }
    }
    for (name in compiled.keys) {
        if (name !in projected) problems += "$name: missing from contract yaml"
    }
    return problems
}

private fun sameValue(left: Any?, right: Any?): Boolean = normalize(left) == normalize(right)

private fun normalize(value: Any?): Any? = if (value is Number) value.toLong() else value

private fun YVal.mapping(what: String): List<Pair<String, YVal>> =
    (this as? YVal.Mapping)?.entries ?: error("$what is not a map")

private fun YVal.seq(what: String): List<YVal> =
    (this as? YVal.Seq)?.items ?: error("$what is not a list")

private fun YVal.scalar(what: String): Any =
    (this as? YVal.Scalar)?.value ?: error("$what is not a scalar")

private fun YVal.int(what: String): Int {
    val value = scalar(what)
    return value as? Int ?: error("$what is not an int")
}

private fun YVal.string(what: String): String {
    val value = scalar(what)
    return value as? String ?: error("$what is not a string")
}

private fun YVal.limitScalar(what: String): Any {
    val value = scalar(what)
    if (value is Int || value is String) return value
    error("$what is not an int or string")
}

private fun List<Pair<String, YVal>>.nameKind(what: String): NameKind {
    requireKeys(what, setOf("name", "kind"))
    return NameKind(require("name").string("$what.name"), require("kind").string("$what.kind"))
}

private fun List<Pair<String, YVal>>.density(what: String): NameDpi {
    requireKeys(what, setOf("name", "dpi"))
    return NameDpi(require("name").string("$what.name"), require("dpi").int("$what.dpi"))
}

private fun List<Pair<String, YVal>>.configEntry(what: String): ConfigEntry {
    val allowed = setOf("name", "type", "default", "min", "max")
    val present = map { it.first }.toSet()
    if (present.any { it !in allowed } || "name" !in present || "type" !in present || "default" !in present) {
        error("$what keys $present")
    }
    val defaultValue = require("default").scalar("$what.default")
    if (defaultValue !is Boolean && defaultValue !is Int) error("$what.default is not a bool or int")
    return ConfigEntry(
        name = require("name").string("$what.name"),
        type = require("type").string("$what.type"),
        defaultValue = defaultValue,
        min = optional("min")?.int("$what.min"),
        max = optional("max")?.int("$what.max"),
    )
}

private fun List<Pair<String, YVal>>.profile(what: String): ProfileEntry {
    val allowed = setOf("name", "json", "maxLength", "length")
    val present = map { it.first }.toSet()
    if (present.any { it !in allowed } || "name" !in present || "json" !in present) {
        error("$what keys $present")
    }
    return ProfileEntry(
        name = require("name").string("$what.name"),
        json = require("json").string("$what.json"),
        maxLength = optional("maxLength")?.int("$what.maxLength"),
        length = optional("length")?.int("$what.length"),
    )
}

private fun List<Pair<String, YVal>>.requireKeys(what: String, expected: Set<String>) {
    if (map { it.first }.toSet() != expected) error("$what keys ${map { it.first }}")
}

private fun List<Pair<String, YVal>>.require(key: String): YVal =
    first { it.first == key }.second

private fun List<Pair<String, YVal>>.optional(key: String): YVal? =
    find { it.first == key }?.second
