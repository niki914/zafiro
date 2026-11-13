package com.niki914.zafiro.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

data class PartitionChangeCounts(
    val added: Int = 0,
    val replaced: Int = 0,
    val conflictSkipped: Int = 0,
    val untouched: Int = 0,
)

internal sealed interface PartitionWrite {
    val counts: PartitionChangeCounts

    data class Document(
        val partition: BackupPartition,
        val json: String,
        override val counts: PartitionChangeCounts,
    ) : PartitionWrite

    data class Conversations(
        val conversations: List<ConversationBackup>,
        val entries: List<EntryBackup>,
        val deleteIds: List<String>,
        val appStateJson: String,
        val imageFiles: Map<String, ByteArray>,
        override val counts: PartitionChangeCounts,
    ) : PartitionWrite

    data class Skills(
        val statusJson: String,
        val skills: List<SkillSnapshot>,
        val deleteIds: List<String>,
        override val counts: PartitionChangeCounts,
    ) : PartitionWrite
}

/**
 * 决定一个分区要写成什么样。不碰磁盘。
 * [restore] 为真时按覆盖处理，并且连本地独有的对话也删掉，用来把分区退回快照。
 */
internal fun planPartition(
    partition: BackupPartition,
    entries: Map<String, ByteArray>,
    local: BackupSnapshot,
    strategy: BackupStrategy,
    restore: Boolean,
    imageDir: File,
): PartitionWrite {
    return when (partition) {
        BackupPartition.Conversations -> planConversations(entries, local, strategy, restore, imageDir)
        BackupPartition.Skills -> planSkills(entries, local, strategy, restore)
        BackupPartition.Preferences -> planPreferences(entries, local)
        else -> planJsonPartition(partition, entries, local, strategy, restore)
    }
}

internal fun BackupPartition.itemIdKey(): String? {
    return when (this) {
        BackupPartition.PyTools,
        BackupPartition.TextActions,
        -> "name"
        BackupPartition.Memory,
        BackupPartition.Models,
        BackupPartition.McpServers,
        BackupPartition.ExecutionRules,
        BackupPartition.TakeoverRules,
        BackupPartition.MaskingRules,
        -> "id"
        else -> null
    }
}

internal fun localJson(partition: BackupPartition, local: BackupSnapshot): String {
    return when (partition) {
        BackupPartition.Memory -> local.memoryJson
        BackupPartition.Models -> local.modelsJson
        BackupPartition.Preferences -> local.appStateJson
        BackupPartition.McpServers -> local.mcpServersJson
        BackupPartition.McpService -> local.mcpServiceJson
        BackupPartition.BuiltinTools -> local.builtinToolsJson
        BackupPartition.PyTools -> local.pyToolsJson
        BackupPartition.TextActions -> local.textActionsJson
        BackupPartition.ExecutionRules -> local.executionRulesJson
        BackupPartition.TakeoverRules -> local.takeoverRulesJson
        BackupPartition.MaskingRules -> local.maskingRulesJson
        else -> error(partition.id)
    }
}

private fun planJsonPartition(
    partition: BackupPartition,
    entries: Map<String, ByteArray>,
    local: BackupSnapshot,
    strategy: BackupStrategy,
    restore: Boolean,
): PartitionWrite {
    val raw = entries[partition.jsonName]?.toString(Charsets.UTF_8) ?: error("missing ${partition.id}")
    val text = if (partition == BackupPartition.PyTools) {
        restoreDownloadDirs(raw, local.pyInstallApkDir, local.pyDownloadFileDir)
    } else {
        raw
    }
    val listKey = partition.listKey
    if (listKey == null) {
        return PartitionWrite.Document(partition, text, documentCounts())
    }
    val idKey = partition.itemIdKey() ?: error(partition.id)
    val replaceList = restore || strategy == BackupStrategy.Overwrite
    if (replaceList) {
        return PartitionWrite.Document(
            partition,
            text,
            replaceCounts(localJson(partition, local), text, listKey, idKey),
        )
    }
    val merged = mergeLists(localJson(partition, local), text, partition, idKey)
    return PartitionWrite.Document(partition, merged.json, merged.counts)
}

private fun planPreferences(
    entries: Map<String, ByteArray>,
    local: BackupSnapshot,
): PartitionWrite {
    val imported = entries[BackupPartition.Preferences.jsonName]?.toString(Charsets.UTF_8)
        ?: error("missing preferences")
    val json = overlayPreferences(local.appStateJson, imported)
    return PartitionWrite.Document(BackupPartition.Preferences, json, documentCounts())
}

internal fun overlayPreferences(localJson: String, preferencesJson: String): String {
    val local = parseObjectOrNull(localJson) ?: JsonObject(emptyMap())
    val imported = parseObjectOrNull(preferencesJson) ?: error("preferences")
    val result = LinkedHashMap<String, JsonElement>()
    local.forEach { (key, value) ->
        if (key in neverExportKeys || key in conversationAppStateKeys || key == ONBOARDING_COMPLETED) {
            result[key] = value
        }
    }
    imported.forEach { (key, value) ->
        if (key in neverExportKeys || key in conversationAppStateKeys) return@forEach
        result[key] = value
    }
    return JsonObject(result).toString()
}

internal fun restoreDownloadDirs(json: String, installDir: String, downloadDir: String): String {
    var text = json
    if (installDir.isNotEmpty()) {
        text = text.replace("$DOWNLOADS_PLACEHOLDER/py_install_apk", installDir)
    }
    if (downloadDir.isNotEmpty()) {
        text = text.replace("$DOWNLOADS_PLACEHOLDER/py_download_file", downloadDir)
    }
    return text
}

private fun planConversations(
    entries: Map<String, ByteArray>,
    local: BackupSnapshot,
    strategy: BackupStrategy,
    restore: Boolean,
    imageDir: File,
): PartitionWrite {
    val raw = entries[BackupPartition.Conversations.jsonName]?.toString(Charsets.UTF_8)
        ?: error("missing conversations")
    val document = backupJson.decodeFromString(ConversationDocument.serializer(), raw)
    val replaceSame = restore || strategy == BackupStrategy.Overwrite
    val localById = local.conversations.associateBy { it.id }
    val importRows = document.conversations.distinctBy { it.id }
    val importIds = importRows.map { it.id }.toSet()
    val upserts = mutableListOf<ConversationBackup>()
    var added = 0
    var replaced = 0
    var skipped = 0
    importRows.forEach { row ->
        val backup = row.toBackup()
        when {
            row.id !in localById -> {
                added++
                upserts += backup
            }
            replaceSame -> {
                replaced++
                upserts += backup
            }
            else -> skipped++
        }
    }
    val deleteIds = mutableListOf<String>()
    var untouched = 0
    local.conversations.forEach { row ->
        if (row.id in importIds) return@forEach
        if (restore) deleteIds += row.id else untouched++
    }
    val upsertIds = upserts.map { it.id }.toSet()
    val blobs = blobBytes(entries)
    val imageFiles = linkedMapOf<String, ByteArray>()
    val writtenEntries = document.entries
        .filter { it.conversationId in upsertIds }
        .map { entry ->
            EntryBackup(
                conversationId = entry.conversationId,
                id = entry.id,
                parentId = entry.parentId,
                timestamp = entry.timestamp,
                messageJson = rewriteBlobRefs(entry.message.toString(), blobs, imageDir, imageFiles),
            )
        }
    return PartitionWrite.Conversations(
        conversations = upserts,
        entries = writtenEntries,
        deleteIds = deleteIds,
        appStateJson = overlayConversationState(local.appStateJson, document),
        imageFiles = imageFiles,
        counts = PartitionChangeCounts(added, replaced, skipped, untouched),
    )
}

private fun ConversationWire.toBackup(): ConversationBackup {
    return ConversationBackup(
        id = id,
        title = title,
        titleEdited = titleEdited,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        turnCount = turnCount,
        leafId = leafId,
        configId = configId,
        hasUnread = hasUnread,
        rootId = rootId.ifBlank { id },
        parentId = parentId,
        forkTurn = forkTurn,
    )
}

private fun overlayConversationState(localJson: String, document: ConversationDocument): String {
    val local = parseObjectOrNull(localJson) ?: JsonObject(emptyMap())
    return local
        .withKey("last_opened_conversation_id", JsonPrimitive(document.lastOpenedConversationId))
        .withKey("draft_text", JsonPrimitive(document.draftText))
        .withKey("pinned_conversations", document.pinnedConversations)
        .toString()
}

private fun planSkills(
    entries: Map<String, ByteArray>,
    local: BackupSnapshot,
    strategy: BackupStrategy,
    restore: Boolean,
): PartitionWrite {
    val archive = skillArchive(entries)
    val replaceAll = restore || strategy == BackupStrategy.Overwrite
    val importIds = archive.files.keys + enabledKeys(archive.statusJson)
    val localDirIds = local.skills.map { it.id }.toSet()
    val localIds = localDirIds + enabledKeys(local.skillsStatusJson)
    val counts = if (replaceAll) {
        PartitionChangeCounts(
            added = importIds.count { it !in localIds },
            replaced = importIds.count { it in localIds },
        )
    } else {
        PartitionChangeCounts(
            added = importIds.count { it !in localIds },
            conflictSkipped = importIds.count { it in localIds },
            untouched = localIds.count { it !in importIds },
        )
    }
    val writeIds: List<String> = if (replaceAll) {
        archive.files.keys.toList()
    } else {
        archive.files.keys.filter { it !in localIds }
    }
    val skills = writeIds.map { id -> SkillSnapshot(id = id, files = archive.files.getValue(id)) }
    val deleteIds = if (replaceAll) localDirIds.filter { it !in archive.files.keys } else emptyList()
    val status = if (replaceAll) archive.statusJson else mergeStatus(local.skillsStatusJson, archive.statusJson)
    return PartitionWrite.Skills(status, skills, deleteIds, counts)
}

private data class SkillArchive(
    val statusJson: String,
    val files: Map<String, Map<String, ByteArray>>,
)

private fun skillArchive(entries: Map<String, ByteArray>): SkillArchive {
    val status = entries["$SKILLS_ROOT_PREFIX/status.json"]?.toString(Charsets.UTF_8)
        ?: """{"enabled":{}}"""
    val skillIds = entries.keys.mapNotNull { name ->
        if (!name.startsWith("$SKILLS_ROOT_PREFIX/") || !name.endsWith("/SKILL.md")) return@mapNotNull null
        val id = name.removePrefix("$SKILLS_ROOT_PREFIX/").removeSuffix("/SKILL.md")
        id.takeIf { safeSkillId(it) }
    }.toSet()
    val files = linkedMapOf<String, LinkedHashMap<String, ByteArray>>()
    entries.forEach { (name, bytes) ->
        if (!name.startsWith("$SKILLS_ROOT_PREFIX/")) return@forEach
        val relativeToRoot = name.removePrefix("$SKILLS_ROOT_PREFIX/")
        if (relativeToRoot == "status.json") return@forEach
        val id = skillIds
            .filter { relativeToRoot == it || relativeToRoot.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: return@forEach
        val relative = relativeToRoot.removePrefix("$id/")
        if (!safeRelative(relative)) return@forEach
        files.getOrPut(id) { linkedMapOf() }[relative] = bytes
    }
    return SkillArchive(status, files)
}

private fun mergeStatus(localJson: String, importJson: String): String {
    val result = LinkedHashMap<String, JsonElement>()
    enabledObject(localJson).forEach { (key, value) -> result[key] = value }
    enabledObject(importJson).forEach { (key, value) ->
        if (key !in result) result[key] = value
    }
    return JsonObject(mapOf("enabled" to JsonObject(result))).toString()
}

private fun enabledObject(json: String): JsonObject {
    return parseObjectOrNull(json)?.get("enabled") as? JsonObject ?: JsonObject(emptyMap())
}

private fun enabledKeys(json: String): Set<String> = enabledObject(json).keys

private data class MergedJson(
    val json: String,
    val counts: PartitionChangeCounts,
)

private fun mergeLists(
    localJson: String,
    importJson: String,
    partition: BackupPartition,
    idKey: String,
): MergedJson {
    val listKey = partition.listKey ?: error(partition.id)
    val localRoot = parseObjectOrNull(localJson) ?: JsonObject(emptyMap())
    val importRoot = parseObjectOrNull(importJson) ?: error("bad ${partition.id}")
    val localItems = (localRoot[listKey] as? JsonArray)?.toList().orEmpty()
    val importItems = (importRoot[listKey] as? JsonArray)?.toList() ?: error("bad ${partition.id}")
    val localIds = linkedSetOf<String>()
    localItems.forEach { item -> item.itemId(idKey)?.let { localIds += it } }
    val seenImport = linkedSetOf<String>()
    val newcomers = mutableListOf<JsonElement>()
    var added = 0
    var skipped = 0
    importItems.forEach { item ->
        val id = item.itemId(idKey) ?: return@forEach
        if (!seenImport.add(id)) return@forEach
        if (id in localIds) {
            skipped++
        } else {
            added++
            newcomers += item
        }
    }
    val mergedItems = localItems + newcomers
    val result = LinkedHashMap<String, JsonElement>()
    localRoot.forEach { (key, value) -> result[key] = value }
    result[listKey] = JsonArray(mergedItems)
    if (partition.singletonApply == Apply.Overwrite) {
        importRoot.forEach { (key, value) ->
            if (key == listKey || key == EXPLICIT_KEY) return@forEach
            result[key] = value
        }
    }
    val explicit = partition.honorsExplicitEmpty && mergedItems.isEmpty() && importRoot.explicitEmpty()
    if (explicit) {
        result[EXPLICIT_KEY] = JsonPrimitive(true)
    } else {
        result.remove(EXPLICIT_KEY)
    }
    val counts = PartitionChangeCounts(
        added = added,
        conflictSkipped = skipped,
        untouched = localIds.count { it !in seenImport },
    )
    return MergedJson(JsonObject(result).toString(), counts)
}

private fun replaceCounts(
    localJson: String,
    importJson: String,
    listKey: String,
    idKey: String,
): PartitionChangeCounts {
    val localIds = ((parseObjectOrNull(localJson)?.get(listKey) as? JsonArray) ?: JsonArray(emptyList()))
        .mapNotNull { it.itemId(idKey) }
        .toSet()
    val importItems = (parseObjectOrNull(importJson)?.get(listKey) as? JsonArray) ?: error("bad json")
    val seen = linkedSetOf<String>()
    var added = 0
    var replaced = 0
    importItems.forEach { item ->
        val id = item.itemId(idKey) ?: return@forEach
        if (!seen.add(id)) return@forEach
        if (id in localIds) replaced++ else added++
    }
    return PartitionChangeCounts(added = added, replaced = replaced)
}

private fun documentCounts(): PartitionChangeCounts = PartitionChangeCounts(replaced = 1)

private fun JsonElement.itemId(idKey: String): String? {
    val obj = this as? JsonObject ?: return null
    return (obj[idKey] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.explicitEmpty(): Boolean {
    val marker = this[EXPLICIT_KEY] as? JsonPrimitive ?: return false
    return marker.booleanOrNull == true
}

private fun blobBytes(entries: Map<String, ByteArray>): Map<String, ByteArray> {
    return entries.filterKeys { name ->
        name.startsWith("blobs/") && name.endsWith(".jpg") && !name.startsWith("blobs/unpacked/")
    }
}

private val blobRef = Regex("""blobs/([0-9a-f]{64})\.jpg""")

private fun rewriteBlobRefs(
    json: String,
    blobs: Map<String, ByteArray>,
    imageDir: File,
    imageFiles: MutableMap<String, ByteArray>,
): String {
    var text = json
    blobRef.findAll(json)
        .map { it.value to it.groupValues[1] }
        .distinctBy { it.first }
        .sortedByDescending { it.first.length }
        .forEach { (ref, hash) ->
            val bytes = blobs[ref] ?: return@forEach
            val path = File(imageDir, "${hash.take(8)}.jpg").absolutePath
            imageFiles.putIfAbsent(path, bytes)
            text = text.replace(ref, path)
        }
    return text
}

private const val EXPLICIT_KEY = "_explicit"
private const val SKILLS_ROOT_PREFIX = "partitions/skills"
