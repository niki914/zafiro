package com.niki914.zafiro.backup

import com.niki914.okia.message.Message
import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private const val SKILLS_PREFIX = "partitions/skills"

internal fun inspectEntries(entries: Map<String, ByteArray>): InspectResult {
    val manifestBytes = entries["manifest.json"] ?: return InspectResult.Rejected(RejectReason.MissingManifest)
    val manifestText = manifestBytes.toString(Charsets.UTF_8)
    val root = parseObjectOrNull(manifestText) ?: return InspectResult.Rejected(RejectReason.MissingManifest)
    val version = (root["format_version"] as? JsonPrimitive)?.intOrNull
        ?: return InspectResult.Rejected(RejectReason.MissingManifest)
    if (version > FORMAT_VERSION) {
        return InspectResult.Rejected(RejectReason.NewerFormat)
    }
    val manifest = tryOrNull {
        backupJson.decodeFromJsonElement(ManifestDocument.serializer(), root)
    } ?: return InspectResult.Rejected(RejectReason.MissingManifest)
    val secrets = SecretsMode.fromWire(manifest.secrets)
        ?: return InspectResult.Rejected(RejectReason.MissingManifest)

    val texts = linkedMapOf<String, String>()
    val partitions = linkedMapOf<String, PartitionSummary>()
    BackupPartition.entries.forEach { partition ->
        val summary = summaryOf(partition, entries, texts) ?: return@forEach
        partitions[partition.id] = summary
    }
    val external = texts["conversations"]?.let { countExternal(it) } ?: 0
    val blobs = entries.keys.filter { it.startsWith("blobs/") && !it.endsWith("/") }.sorted()
    return InspectResult.Ok(
        summary = BackupSummary(
            formatVersion = version,
            appVersion = manifest.appVersion,
            createdAtMillis = manifest.createdAt,
            secrets = secrets,
            partitions = partitions,
            externalAttachmentPaths = external,
            ignoredPartitions = ignoredPartitions(entries.keys),
        ),
        texts = texts,
        blobNames = blobs,
    )
}

private fun summaryOf(
    partition: BackupPartition,
    entries: Map<String, ByteArray>,
    texts: MutableMap<String, String>,
): PartitionSummary? {
    val shape = if (partition == BackupPartition.Skills) {
        if (entries.keys.none { it.startsWith("$SKILLS_PREFIX/") }) return null
        val count = entries.keys.count { it.startsWith("$SKILLS_PREFIX/") && it.endsWith("/SKILL.md") }
        val state = if (count == 0) PartitionState.Empty else PartitionState.Items
        ListShape(state, count)
    } else {
        val bytes = entries[partition.jsonName] ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        texts[partition.id] = text
        listShape(text, partition)
    }
    return PartitionSummary(
        id = partition.id,
        groupId = partition.group.id,
        state = shape.state,
        count = shape.count,
        apply = partition.apply,
        singletonApply = partition.singletonApply,
    )
}

private fun countExternal(conversationsJson: String): Int {
    val document = tryOrNull {
        backupJson.decodeFromString(ConversationDocument.serializer(), conversationsJson)
    } ?: return 0
    return document.entries.sumOf { entry ->
        val message = tryOrNull {
            backupJson.decodeFromJsonElement(Message.serializer(), entry.message)
        } ?: return@sumOf 0
        externalAttachmentCount(message)
    }
}

private fun ignoredPartitions(names: Collection<String>): List<String> {
    val known = BackupPartition.entries.map { it.id }.toSet()
    val ignored = linkedSetOf<String>()
    names.forEach { name ->
        if (name == "manifest.json" || name.startsWith("blobs/")) return@forEach
        if (!name.startsWith("partitions/")) {
            val top = name.substringBefore("/").ifBlank { name }
            if (top.isNotEmpty()) ignored += top
            return@forEach
        }
        val rest = name.removePrefix("partitions/")
        if (rest == "skills" || rest.startsWith("skills/")) return@forEach
        val id = rest.substringBefore("/").removeSuffix(".json")
        if (id.isNotEmpty() && id !in known) ignored += id
    }
    return ignored.sorted()
}
