package com.niki914.zafiro.backup

import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal val backupJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal const val FORMAT_VERSION: Int = 1
internal const val DOWNLOADS_PLACEHOLDER: String = "__ZAFIRO_DOWNLOADS__"

/** 引导完成状态。随偏好导出；包里没有这个键时保留本机的值。 */
internal const val ONBOARDING_COMPLETED = "onboarding_completed"

/** 只留在本机的偏好。 */
internal val neverExportKeys = setOf(
    "phone_use_consent_granted",
    "last_notified_update_version",
)

internal val conversationAppStateKeys = setOf(
    "pinned_conversations",
    "last_opened_conversation_id",
    "draft_text",
)

internal fun parseObjectOrNull(json: String): JsonObject? {
    return tryOrNull {
        backupJson.parseToJsonElement(json) as? JsonObject
    }
}

internal fun JsonObject.withKey(key: String, value: JsonElement): JsonObject {
    val map = LinkedHashMap<String, JsonElement>(size + 1)
    for ((entryKey, entryValue) in this) {
        map[entryKey] = entryValue
    }
    map[key] = value
    return JsonObject(map)
}

internal fun preferencesDocument(appStateJson: String): String {
    val root = parseObjectOrNull(appStateJson) ?: JsonObject(emptyMap())
    val kept = LinkedHashMap<String, JsonElement>()
    for ((key, value) in root) {
        if (key in neverExportKeys || key in conversationAppStateKeys) continue
        kept[key] = value
    }
    return JsonObject(kept).toString()
}

internal fun appStateText(appStateJson: String, key: String): String {
    val value = parseObjectOrNull(appStateJson)?.get(key) as? JsonPrimitive
    return value?.contentOrNull.orEmpty()
}

internal fun appStateElement(appStateJson: String, key: String): JsonElement {
    return parseObjectOrNull(appStateJson)?.get(key) ?: JsonArray(emptyList())
}

internal fun stripModelSecrets(json: String): String {
    val root = parseObjectOrNull(json) ?: return json
    val configs = root["configs"] as? JsonArray ?: return json
    val stripped = JsonArray(
        configs.map { element ->
            val obj = element as? JsonObject ?: return@map element
            if ("api_key" !in obj) element else obj.withKey("api_key", JsonPrimitive(""))
        },
    )
    return root.withKey("configs", stripped).toString()
}

internal fun stripMcpHeaderSecrets(json: String): String {
    val root = parseObjectOrNull(json) ?: return json
    val servers = root["servers"] as? JsonArray ?: return json
    val stripped = JsonArray(
        servers.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val headers = obj["headers"] as? JsonObject ?: return@map element
            val cleared = JsonObject(headers.mapValues { JsonPrimitive("") })
            obj.withKey("headers", cleared)
        },
    )
    return root.withKey("servers", stripped).toString()
}

internal fun stripBearer(json: String): String {
    val root = parseObjectOrNull(json) ?: return json
    if ("bearerToken" !in root) return json
    return root.withKey("bearerToken", JsonPrimitive("")).toString()
}

internal fun replaceDownloadDirs(json: String, installDir: String, downloadDir: String): String {
    var text = json
    if (installDir.isNotEmpty()) {
        text = text.replace(installDir, "$DOWNLOADS_PLACEHOLDER/py_install_apk")
    }
    if (downloadDir.isNotEmpty()) {
        text = text.replace(downloadDir, "$DOWNLOADS_PLACEHOLDER/py_download_file")
    }
    return text
}

internal data class ListShape(
    val state: PartitionState,
    val count: Int,
)

/** 从分区 JSON 读出条数和三种空状态。解析失败时当成一份文档，避免把坏文件说成空列表。 */
internal fun listShape(json: String, partition: BackupPartition): ListShape {
    if (partition == BackupPartition.Skills) {
        return ListShape(PartitionState.Empty, 0)
    }
    if (partition == BackupPartition.BuiltinTools) {
        val enabled = parseObjectOrNull(json)?.get("enabled") as? JsonObject
        val count = enabled?.size ?: 0
        val state = if (count == 0) PartitionState.Empty else PartitionState.Document
        return ListShape(state, count)
    }
    if (partition.listKey == null) {
        return ListShape(PartitionState.Document, 1)
    }
    val root = parseObjectOrNull(json) ?: return ListShape(PartitionState.Document, 1)
    val array = root[partition.listKey] as? JsonArray
        ?: return ListShape(PartitionState.Document, 1)
    if (array.isEmpty() && partition.honorsExplicitEmpty && root.explicitEmpty()) {
        return ListShape(PartitionState.ExplicitEmpty, 0)
    }
    if (array.isEmpty()) {
        return ListShape(PartitionState.Empty, 0)
    }
    return ListShape(PartitionState.Items, array.size)
}

private fun JsonObject.explicitEmpty(): Boolean {
    val marker = this["_explicit"] as? JsonPrimitive ?: return false
    return marker.booleanOrNull == true
}
