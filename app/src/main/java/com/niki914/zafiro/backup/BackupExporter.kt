package com.niki914.zafiro.backup

import java.io.File

/**
 * 写出 `.zafiro`。搬家文件传 [BackupRequest.includeSecrets] = false，
 * 日后的应用前快照传 true。分区形态相同。
 */
internal object BackupExporter {

    fun export(request: BackupRequest, snapshot: BackupSnapshot, output: File) {
        writeZip(output, entries(request, snapshot))
    }

    fun inspect(file: File): InspectResult {
        return when (val zip = readZip(file)) {
            null -> InspectResult.Rejected(RejectReason.NotAnArchive)
            ZipRead.Unsafe -> InspectResult.Rejected(RejectReason.UnsafePath)
            is ZipRead.Ok -> inspectEntries(zip.entries)
        }
    }

    internal fun entries(request: BackupRequest, snapshot: BackupSnapshot): List<ArchiveEntry> {
        val secrets = if (request.includeSecrets) SecretsMode.Included else SecretsMode.Stripped
        val built = linkedMapOf<String, ByteArray>()
        var externalPaths = 0
        if (BackupGroup.Conversations in request.groups) {
            val rewritten = rewriteEntries(snapshot.entries, snapshot.readImage)
            externalPaths = rewritten.externalAttachmentPaths
            built[BackupPartition.Conversations.jsonName] = conversationBytes(snapshot, rewritten)
            built[BackupPartition.Memory.jsonName] = snapshot.memoryJson.toByteArray()
            rewritten.blobs.forEach { (name, bytes) -> built[name] = bytes }
        }
        if (BackupGroup.Config in request.groups) {
            val models = if (request.includeSecrets) {
                snapshot.modelsJson
            } else {
                stripModelSecrets(snapshot.modelsJson)
            }
            built[BackupPartition.Models.jsonName] = models.toByteArray()
            built[BackupPartition.Preferences.jsonName] =
                preferencesDocument(snapshot.appStateJson).toByteArray()
        }
        if (BackupGroup.Tools in request.groups) {
            val servers = if (request.includeSecrets) {
                snapshot.mcpServersJson
            } else {
                stripMcpHeaderSecrets(snapshot.mcpServersJson)
            }
            val service = if (request.includeSecrets) {
                snapshot.mcpServiceJson
            } else {
                stripBearer(snapshot.mcpServiceJson)
            }
            built[BackupPartition.McpServers.jsonName] = servers.toByteArray()
            built[BackupPartition.McpService.jsonName] = service.toByteArray()
            built[BackupPartition.BuiltinTools.jsonName] = snapshot.builtinToolsJson.toByteArray()
            built[BackupPartition.PyTools.jsonName] = replaceDownloadDirs(
                snapshot.pyToolsJson,
                snapshot.pyInstallApkDir,
                snapshot.pyDownloadFileDir,
            ).toByteArray()
            built[BackupPartition.TextActions.jsonName] = snapshot.textActionsJson.toByteArray()
        }
        if (BackupGroup.Skills in request.groups) {
            built += skillEntries(snapshot)
        }
        if (BackupGroup.ExecutionRules in request.groups) {
            built[BackupPartition.ExecutionRules.jsonName] = snapshot.executionRulesJson.toByteArray()
        }
        if (BackupGroup.TakeoverRules in request.groups) {
            built[BackupPartition.TakeoverRules.jsonName] = snapshot.takeoverRulesJson.toByteArray()
        }
        if (BackupGroup.MaskingRules in request.groups) {
            built[BackupPartition.MaskingRules.jsonName] = snapshot.maskingRulesJson.toByteArray()
        }
        val manifest = manifestBytes(request, secrets, built, externalPaths)
        return listOf(ArchiveEntry("manifest.json", manifest)) +
            built.map { (name, bytes) -> ArchiveEntry(name, bytes) }
    }

    private fun conversationBytes(snapshot: BackupSnapshot, rewritten: RewrittenEntries): ByteArray {
        val document = ConversationDocument(
            conversations = snapshot.conversations.map { row ->
                ConversationWire(
                    id = row.id,
                    title = row.title,
                    titleEdited = row.titleEdited,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    lastMessagePreview = row.lastMessagePreview,
                    turnCount = row.turnCount,
                    leafId = row.leafId,
                    configId = row.configId,
                    hasUnread = row.hasUnread,
                    rootId = row.rootId.ifBlank { row.id },
                    parentId = row.parentId,
                    forkTurn = row.forkTurn,
                )
            },
            entries = snapshot.entries.mapIndexed { index, row ->
                EntryWire(
                    conversationId = row.conversationId,
                    id = row.id,
                    parentId = row.parentId,
                    timestamp = row.timestamp,
                    message = rewritten.messages[index],
                )
            },
            lastOpenedConversationId = appStateText(
                snapshot.appStateJson,
                "last_opened_conversation_id",
            ),
            draftText = appStateText(snapshot.appStateJson, "draft_text"),
            pinnedConversations = appStateElement(snapshot.appStateJson, "pinned_conversations"),
        )
        return backupJson.encodeToString(ConversationDocument.serializer(), document).toByteArray()
    }

    private fun skillEntries(snapshot: BackupSnapshot): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        out["$SKILLS_PREFIX/status.json"] = snapshot.skillsStatusJson.toByteArray()
        snapshot.skills.forEach { skill ->
            if (!safeSkillId(skill.id)) return@forEach
            packSkillFiles(skill).forEach { (relative, bytes) ->
                out["$SKILLS_PREFIX/${skill.id}/$relative"] = bytes
            }
        }
        return out
    }

    private fun manifestBytes(
        request: BackupRequest,
        secrets: SecretsMode,
        files: Map<String, ByteArray>,
        externalPaths: Int,
    ): ByteArray {
        val present = BackupPartition.entries.filter { partition ->
            partition.group in request.groups
        }
        val groups = present.groupBy { it.group }.mapKeys { it.key.id }
            .mapValues { (_, partitions) -> partitions.map { it.id } }
        val wires = present.associate { partition ->
            val shape = shapeOf(partition, files)
            partition.id to PartitionWire(
                group = partition.group.id,
                count = shape.count,
                state = shape.state.wire,
                apply = partition.apply.wire,
                singletonApply = partition.singletonApply?.wire,
            )
        }
        val document = ManifestDocument(
            formatVersion = FORMAT_VERSION,
            appVersion = request.appVersion,
            createdAt = request.createdAtMillis,
            secrets = secrets.wire,
            groups = groups,
            partitions = wires,
            externalAttachmentPaths = externalPaths,
        )
        return backupJson.encodeToString(ManifestDocument.serializer(), document).toByteArray()
    }

    private fun shapeOf(partition: BackupPartition, files: Map<String, ByteArray>): ListShape {
        if (partition == BackupPartition.Skills) {
            val count = files.keys.count { it.startsWith("$SKILLS_PREFIX/") && it.endsWith("/SKILL.md") }
            val state = if (count == 0) PartitionState.Empty else PartitionState.Items
            return ListShape(state, count)
        }
        val bytes = files[partition.jsonName] ?: return ListShape(PartitionState.Empty, 0)
        return listShape(bytes.toString(Charsets.UTF_8), partition)
    }
}

private const val SKILLS_PREFIX = "partitions/skills"

private fun String.toByteArray(): ByteArray = toByteArray(Charsets.UTF_8)
