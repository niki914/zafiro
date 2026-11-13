package com.niki914.zafiro.backup

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.text.FilesBlock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNull
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BackupApplyHarness(
    private val folder: TemporaryFolder,
) {
    fun memoryStore(): MemoryBackupStore {
        return MemoryBackupStore(folder.newFolder("store"), folder.newFolder("snapshots"))
    }

    fun apply(
        source: File,
        groups: Set<BackupGroup>,
        strategy: BackupStrategy,
        store: MemoryBackupStore,
    ): BackupApplyResult = runBlocking {
        withTimeout(5_000) {
            BackupImporter.apply(
                source = source,
                selectedGroups = groups,
                strategy = strategy,
                store = store,
                snapshotsDir = store.snapshotsDir,
                nowMillis = 1_700_000_000_000,
            )
        }
    }

    fun apply(
        source: InputStream,
        groups: Set<BackupGroup>,
        strategy: BackupStrategy,
        store: MemoryBackupStore,
    ): BackupApplyResult = runBlocking {
        withTimeout(5_000) {
            BackupImporter.apply(
                source = source,
                selectedGroups = groups,
                strategy = strategy,
                store = store,
                snapshotsDir = store.snapshotsDir,
                nowMillis = 1_700_000_000_000,
            )
        }
    }

    fun allGroups(includeSecrets: Boolean): BackupRequest {
        return BackupRequest(
            groups = BackupGroup.entries.toSet(),
            includeSecrets = includeSecrets,
            createdAtMillis = 1L,
            appVersion = "1.10.1",
        )
    }

    fun request(vararg groups: BackupGroup): BackupRequest {
        return BackupRequest(groups.toSet(), createdAtMillis = 1L, appVersion = "1.10.1")
    }

    fun archive(
        request: BackupRequest,
        snapshot: BackupSnapshot,
        includeSecrets: Boolean = request.includeSecrets,
    ): File {
        val file = folder.newFile()
        BackupExporter.export(request.copy(includeSecrets = includeSecrets), snapshot, file)
        return file
    }

    fun rawZip(entries: Map<String, String>): File {
        val file = folder.newFile()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    fun withExtraBlob(file: File, bytes: ByteArray): File {
        val packed = (readZip(file) as ZipRead.Ok).entries
        val rebuilt = folder.newFile()
        val entries = packed.map { (name, value) -> ArchiveEntry(name, value) } +
            ArchiveEntry("blobs/${sha256Hex(bytes)}.jpg", bytes)
        writeZip(rebuilt, entries)
        return rebuilt
    }

    fun roundTripSnapshot(store: MemoryBackupStore, imagePath: String): BackupSnapshot {
        val attachment = FilesBlock.block(listOf(FileRef("/storage/emulated/0/Download/report.pdf")))!!
        return BackupSnapshot(
            appStateJson = """{"draft_text":"hello","last_opened_conversation_id":"c1","pinned_conversations":[{"id":"c1","pinned_at":5}],"theme_mode":"dark"}""",
            modelsJson = """{"active_id":"m","prompt":"be brief","configs":[{"id":"m","api_key":"sk-live","endpoint":"https://example.test"}]}""",
            memoryJson = """{"memories":[{"id":"mem","content":"likes tea"}]}""",
            mcpServersJson = """{"servers":[{"id":"local","name":"Local","url":"http://127.0.0.1","headers":{"Authorization":"secret-token"}}]}""",
            mcpServiceJson = """{"enabled":true,"bearerToken":"bearer-live"}""",
            builtinToolsJson = """{"version":2,"enabled":{}}""",
            pyToolsJson = """{"tools":[{"name":"py_install_apk","code":"p='${store.pyInstall}'"}]}""",
            textActionsJson = """{"actions":[{"name":"Custom","prompt":"do the thing"}]}""",
            executionRulesJson = """{"rules":[],"_explicit":true}""",
            takeoverRulesJson = """{"default_target":"ZAFIRO","rules":[]}""",
            maskingRulesJson = """{"rules":[{"id":"mask","name":"Mail","enabled":true,"kind":"email","patterns":["a"]}]}""",
            skillsStatusJson = """{"enabled":{"alpha":false}}""",
            skills = listOf(SkillSnapshot(id = "alpha", files = mapOf("SKILL.md" to "body".toByteArray()))),
            conversations = listOf(conversation("c1", "Chat").copy(configId = "m", rootId = "c1")),
            entries = listOf(
                entry(
                    "c1",
                    "e1",
                    Message.User(
                        listOf(
                            ContentBlock.Text(attachment),
                            ContentBlock.Image(imagePath, "image/jpeg"),
                        ),
                    ),
                ),
            ),
            readImage = { path -> if (path == imagePath) File(path).readBytes() else null },
            pyInstallApkDir = store.pyInstall,
            pyDownloadFileDir = store.pyDownload,
        )
    }

    fun canonical(snapshot: BackupSnapshot): String {
        val shapes = listOf(
            BackupPartition.Memory to snapshot.memoryJson,
            BackupPartition.Models to snapshot.modelsJson,
            BackupPartition.Preferences to snapshot.appStateJson,
            BackupPartition.McpServers to snapshot.mcpServersJson,
            BackupPartition.McpService to snapshot.mcpServiceJson,
            BackupPartition.BuiltinTools to snapshot.builtinToolsJson,
            BackupPartition.PyTools to snapshot.pyToolsJson,
            BackupPartition.TextActions to snapshot.textActionsJson,
            BackupPartition.ExecutionRules to snapshot.executionRulesJson,
            BackupPartition.TakeoverRules to snapshot.takeoverRulesJson,
            BackupPartition.MaskingRules to snapshot.maskingRulesJson,
        ).joinToString("\n") { (partition, json) ->
            val shape = listShape(json, partition)
            "${partition.id}:${shape.state.wire}:${shape.count}:${stable(json)}"
        }
        val chats = snapshot.conversations.sortedBy { it.id }.joinToString("\n") { row ->
            listOf(
                row.id,
                row.title,
                row.titleEdited,
                row.createdAt,
                row.updatedAt,
                row.lastMessagePreview,
                row.turnCount,
                row.leafId,
                row.configId,
                row.hasUnread,
                row.rootId,
                row.parentId,
                row.forkTurn,
            ).joinToString(",")
        }
        val messages = snapshot.entries
            .sortedWith(compareBy({ it.conversationId }, { it.id }))
            .joinToString("\n") { row ->
                "${row.conversationId}:${row.id}:${row.parentId}:${row.timestamp}:${canonicalMessage(row.messageJson)}"
            }
        val skills = snapshot.skills.sortedBy { it.id }.joinToString("\n") { skill ->
            val files = skill.files.toSortedMap().entries.joinToString(",") { (name, bytes) ->
                "$name:${sha256Hex(bytes)}"
            }
            "${skill.id}{$files}"
        }
        val skillState = if (snapshot.skills.isEmpty()) "empty" else "items:${snapshot.skills.size}"
        return "$shapes\n--\n$chats\n--\n$messages\n--\n$skillState\n${stable(snapshot.skillsStatusJson)}\n$skills"
    }

    fun canonicalMessage(json: String): String {
        var text = json
        val decoded = backupJson.decodeFromString(Message.serializer(), json)
        imagePaths(decoded).sortedByDescending { it.length }.forEach { path ->
            val file = File(path)
            if (file.isFile && path.startsWith("/")) {
                text = text.replace(path, "img:" + sha256Hex(file.readBytes()))
            }
        }
        val normalized = backupJson.decodeFromString(Message.serializer(), text)
        return backupJson.encodeToString(Message.serializer(), normalized)
    }

    fun stable(json: String): String = stableElement(backupJson.parseToJsonElement(json))

    fun stableElement(element: JsonElement): String {
        return when (element) {
            is JsonObject -> element.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key:${stableElement(value)}" }
            is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { stableElement(it) }
            else -> element.toString()
        }
    }

    fun conversation(id: String, title: String): ConversationBackup {
        return ConversationBackup(id = id, title = title, rootId = id)
    }

    fun entry(conversationId: String, id: String, text: String): EntryBackup {
        return entry(conversationId, id, Message.User(listOf(ContentBlock.Text(text))))
    }

    fun entry(conversationId: String, id: String, message: Message): EntryBackup {
        return EntryBackup(
            conversationId = conversationId,
            id = id,
            messageJson = backupJson.encodeToString(Message.serializer(), message),
        )
    }

    fun messageText(entry: EntryBackup): String {
        val message = backupJson.decodeFromString(Message.serializer(), entry.messageJson) as Message.User
        return (message.content.single() as ContentBlock.Text).text
    }

    fun imagePathOf(json: String): String {
        val message = backupJson.decodeFromString(Message.serializer(), json) as Message.User
        return (message.content.single() as ContentBlock.Image).path
    }

    fun freshFile(store: MemoryBackupStore, bytes: ByteArray): String {
        val file = File(store.imageDir(), "fresh-source.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun freshPath(store: MemoryBackupStore, bytes: ByteArray): String {
        return File(store.imageDir(), "${sha256Hex(bytes).take(8)}.jpg").absolutePath
    }

    fun configIds(json: String): List<String> {
        val configs = parseObjectOrNull(json)!!["configs"] as JsonArray
        return configs.map { ((it as JsonObject)["id"] as JsonPrimitive).content }
    }
}

internal fun BackupApplyResult.succeeded(): BackupApplyReport {
    val report = reportOrThrow()
    assertNull(report.failure)
    return report
}

internal fun BackupApplyResult.reportOrThrow(): BackupApplyReport {
    return (this as BackupApplyResult.Report).report
}

internal fun MemoryBackupStore.snapshot(): BackupSnapshot = runBlocking { read() }

internal class MemoryBackupStore(
    root: File,
    val snapshotsDir: File,
) : BackupStore {
    val imageRoot = File(root, "image_cache").apply { mkdirs() }
    val skillsRoot = File(root, "skills").apply { mkdirs() }
    private val scratch = File(root, "scratch").apply { mkdirs() }
    var pyInstall = File(root, "downloads/py_install_apk").absolutePath
    var pyDownload = File(root, "downloads/py_download_file").absolutePath
    var appStateJson = "{}"
    var modelsJson = """{"active_id":"","prompt":"","configs":[]}"""
    var memoryJson = """{"memories":[]}"""
    var mcpServersJson = """{"servers":[]}"""
    var mcpServiceJson = """{"enabled":false,"bearerToken":""}"""
    var builtinToolsJson = """{"version":2,"enabled":{}}"""
    var pyToolsJson = """{"tools":[]}"""
    var textActionsJson = """{"actions":[]}"""
    var executionRulesJson = """{"rules":[]}"""
    var takeoverRulesJson = """{"default_target":"ZAFIRO","rules":[]}"""
    var maskingRulesJson = """{"rules":[]}"""
    var skillsStatusJson = """{"enabled":{}}"""
    var conversations = emptyList<ConversationBackup>()
    var entries = emptyList<EntryBackup>()
    var skills = emptyList<SkillSnapshot>()
    var failPartition: BackupPartition? = null
    var imagesWritten = 0

    override suspend fun read(): BackupSnapshot {
        return BackupSnapshot(
            appStateJson = appStateJson,
            modelsJson = modelsJson,
            memoryJson = memoryJson,
            mcpServersJson = mcpServersJson,
            mcpServiceJson = mcpServiceJson,
            builtinToolsJson = builtinToolsJson,
            pyToolsJson = pyToolsJson,
            textActionsJson = textActionsJson,
            executionRulesJson = executionRulesJson,
            takeoverRulesJson = takeoverRulesJson,
            maskingRulesJson = maskingRulesJson,
            skillsStatusJson = skillsStatusJson,
            skills = skills,
            conversations = conversations,
            entries = entries,
            readImage = { path -> File(path).takeIf { it.isFile }?.readBytes() },
            pyInstallApkDir = pyInstall,
            pyDownloadFileDir = pyDownload,
        )
    }

    override fun imageDir(): File = imageRoot

    override fun scratchDir(): File = scratch

    override suspend fun writeDocument(partition: BackupPartition, json: String) {
        bomb(partition)
        when (partition) {
            BackupPartition.Memory -> memoryJson = json
            BackupPartition.Models -> modelsJson = json
            BackupPartition.Preferences -> appStateJson = json
            BackupPartition.McpServers -> mcpServersJson = json
            BackupPartition.McpService -> mcpServiceJson = json
            BackupPartition.BuiltinTools -> builtinToolsJson = json
            BackupPartition.PyTools -> pyToolsJson = json
            BackupPartition.TextActions -> textActionsJson = json
            BackupPartition.ExecutionRules -> executionRulesJson = json
            BackupPartition.TakeoverRules -> takeoverRulesJson = json
            BackupPartition.MaskingRules -> maskingRulesJson = json
            BackupPartition.Conversations, BackupPartition.Skills -> error(partition.id)
        }
    }

    override suspend fun writeConversations(
        conversations: List<ConversationBackup>,
        entries: List<EntryBackup>,
        deleteIds: List<String>,
        appStateJson: String,
        imageFiles: Map<String, ByteArray>,
    ) {
        bomb(BackupPartition.Conversations)
        imageFiles.forEach { (path, bytes) ->
            if (writeImageIfAbsent(imageDir(), path, bytes)) imagesWritten++
        }
        val drop = deleteIds.toSet() + conversations.map { it.id }
        val upsertIds = conversations.map { it.id }.toSet()
        this.entries = this.entries.filter { it.conversationId !in drop } + entries
        this.conversations = this.conversations.filter { it.id !in deleteIds && it.id !in upsertIds } + conversations
        this.appStateJson = appStateJson
    }

    override suspend fun writeSkills(
        statusJson: String,
        skills: List<SkillSnapshot>,
        deleteIds: List<String>,
    ) {
        bomb(BackupPartition.Skills)
        val incoming = skills.map { it.id }.toSet()
        this.skills = this.skills.filter { it.id !in deleteIds && it.id !in incoming } + skills
        skillsStatusJson = statusJson
        writeSkillTree(skillsRoot, statusJson, skills, deleteIds)
    }

    fun clear() {
        appStateJson = "{}"
        modelsJson = """{"active_id":"","prompt":"","configs":[]}"""
        memoryJson = """{"memories":[]}"""
        mcpServersJson = """{"servers":[]}"""
        mcpServiceJson = """{"enabled":false,"bearerToken":""}"""
        builtinToolsJson = """{"version":2,"enabled":{}}"""
        pyToolsJson = """{"tools":[]}"""
        textActionsJson = """{"actions":[]}"""
        executionRulesJson = """{"rules":[]}"""
        takeoverRulesJson = """{"default_target":"ZAFIRO","rules":[]}"""
        maskingRulesJson = """{"rules":[]}"""
        skillsStatusJson = """{"enabled":{}}"""
        conversations = emptyList()
        entries = emptyList()
        skills = emptyList()
        imagesWritten = 0
        imageRoot.listFiles()?.forEach { it.deleteRecursively() }
        skillsRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun marker(): String {
        return listOf(appStateJson, modelsJson, memoryJson, executionRulesJson, conversations.joinToString { it.id + it.title })
            .joinToString("|")
    }

    fun snapshots(): List<File> {
        return snapshotsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("snapshot-") }
            .orEmpty()
    }

    private fun bomb(partition: BackupPartition) {
        if (failPartition == partition) error("write failed")
    }
}
