// 保护：.zafiro 的导出与检视契约。分区条数被数错、三种空状态被折成一种、
// 密钥被写进搬家文件、同一张图打成两份、会话里留下绝对路径、只导出对话时丢掉
// config_id、更高格式版本被当成当前版本、未知分区导致整包失败、路径含 .. 仍被接受、
// 显式空标记在写出时被重新编码丢掉、引导完成状态没进偏好，这些改动会让检视或下一次恢复做错。
package com.niki914.zafiro.backup

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.text.FilesBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun inspectCountsMatchSourceRows() {
        val snapshot = BackupSnapshot(
            appStateJson = """{"theme_mode":"dark"}""",
            modelsJson = """{"active_id":"m","prompt":"","configs":[{"id":"m"}]}""",
            memoryJson = """{"memories":[{},{},{}]}""",
            mcpServersJson = """{"servers":[{},{},{},{}]}""",
            mcpServiceJson = """{"enabled":false,"bearerToken":""}""",
            builtinToolsJson = """{"version":2,"enabled":{"a":true,"b":false,"c":true,"d":true,"e":false}}""",
            pyToolsJson = """{"tools":[{},{},{},{},{},{}]}""",
            textActionsJson = """{"actions":[{},{}]}""",
            executionRulesJson = """{"rules":[{},{}]}""",
            takeoverRulesJson = """{"default_target":"ZAFIRO","rules":[{},{},{}]}""",
            maskingRulesJson = """{"rules":[{},{},{},{}]}""",
            skills = listOf(skill("alpha"), skill("beta")),
            conversations = listOf(conversation("c1"), conversation("c2")),
            entries = List(5) { index -> entry("e$index", Message.User(listOf(ContentBlock.Text("hi")))) },
        )
        val counts = inspected(archive(allGroups(), snapshot)).summary.partitions.mapValues { it.value.count }

        assertEquals(
            mapOf(
                "conversations" to 2,
                "memory" to 3,
                "models" to 1,
                "preferences" to 1,
                "mcp_servers" to 4,
                "mcp_service" to 1,
                "skills" to 2,
                "builtin_tools" to 5,
                "py_tools" to 6,
                "text_actions" to 2,
                "execution_rules" to 2,
                "takeover_rules" to 3,
                "masking_rules" to 4,
            ),
            counts,
        )
    }

    @Test
    fun absentEmptyAndExplicitEmptyStayDistinct() {
        val absent = inspected(archive(request(BackupGroup.Config)))
        val empty = inspected(
            archive(request(BackupGroup.ExecutionRules), BackupSnapshot(executionRulesJson = """{"rules":[]}""")),
        )
        val explicit = inspected(
            archive(
                request(BackupGroup.ExecutionRules),
                BackupSnapshot(executionRulesJson = """{"rules":[],"_explicit":true}"""),
            ),
        )

        assertNull(absent.summary.partitions["execution_rules"])
        assertEquals(PartitionState.Empty, empty.summary.partitions.getValue("execution_rules").state)
        assertEquals(PartitionState.ExplicitEmpty, explicit.summary.partitions.getValue("execution_rules").state)
    }

    @Test
    fun defaultExportStripsSecretsAndMarksManifest() {
        val result = inspected(archive(allGroups(), secretSnapshot()))
        val models = parseObjectOrNull(result.texts.getValue("models"))!!
        val config = (models["configs"] as JsonArray).first() as JsonObject
        val server = ((parseObjectOrNull(result.texts.getValue("mcp_servers"))!!["servers"] as JsonArray)
            .first() as JsonObject)
        val header = (server["headers"] as JsonObject)["Authorization"] as JsonPrimitive
        val bearer = parseObjectOrNull(result.texts.getValue("mcp_service"))!!["bearerToken"] as JsonPrimitive

        assertEquals(SecretsMode.Stripped, result.summary.secrets)
        assertEquals(listOf("", "", ""), listOf((config["api_key"] as JsonPrimitive).content, header.content, bearer.content))
        assertEquals("https://example.test", (config["endpoint"] as JsonPrimitive).content)
    }

    @Test
    fun includedSecretsStayInTheArchive() {
        val result = inspected(archive(allGroups(includeSecrets = true), secretSnapshot()))
        val config = ((parseObjectOrNull(result.texts.getValue("models"))!!["configs"] as JsonArray)
            .first() as JsonObject)
        val header = ((((parseObjectOrNull(result.texts.getValue("mcp_servers"))!!["servers"] as JsonArray)
            .first() as JsonObject)["headers"] as JsonObject)["Authorization"] as JsonPrimitive)
        val bearer = parseObjectOrNull(result.texts.getValue("mcp_service"))!!["bearerToken"] as JsonPrimitive

        assertEquals(SecretsMode.Included, result.summary.secrets)
        assertEquals(
            listOf("sk-live", "secret-token", "bearer-live"),
            listOf((config["api_key"] as JsonPrimitive).content, header.content, bearer.content),
        )
    }

    @Test
    fun sameImageBytesArePackedOnce() {
        val bytes = byteArrayOf(4, 5, 6, 7)
        val first = "/data/user/0/app/files/image_cache/one.jpg"
        val second = "/data/user/0/app/files/image_cache/two.jpg"
        val snapshot = BackupSnapshot(
            entries = listOf(
                entry("e1", Message.User(listOf(ContentBlock.Image(first, "image/jpeg")))),
                entry("e2", Message.User(listOf(ContentBlock.Image(second, "image/jpeg")))),
            ),
            readImage = { path -> if (path == first || path == second) bytes else null },
        )

        val result = inspected(archive(request(BackupGroup.Conversations), snapshot))

        assertEquals(listOf("blobs/${sha256Hex(bytes)}.jpg"), result.blobNames)
    }

    @Test
    fun conversationImagePathsAreNotAbsolute() {
        val userPath = "/data/user/0/app/files/image_cache/user1111.jpg"
        val toolPath = "/data/user/0/app/files/image_cache/tool2222.jpg"
        val snapshot = BackupSnapshot(
            entries = listOf(
                entry("e1", Message.User(listOf(ContentBlock.Image(userPath, "image/jpeg")))),
                entry(
                    "e2",
                    Message.ToolResult(
                        callId = "call",
                        toolName = "view_image",
                        outcome = ToolCallOutcome.Success(
                            content = "ok",
                            images = listOf(ContentBlock.Image(toolPath, "image/jpeg")),
                        ),
                    ),
                ),
            ),
            readImage = { byteArrayOf(1, 2, 3) },
        )

        val messages = messagesOf(inspected(archive(request(BackupGroup.Conversations), snapshot)))
        val user = (messages[0] as Message.User).content.single() as ContentBlock.Image
        val tool = ((messages[1] as Message.ToolResult).outcome as ToolCallOutcome.Success).images.single()

        assertTrue(user.path.startsWith("blobs/") && tool.path.startsWith("blobs/"))
        assertFalse(File(user.path).isAbsolute || File(tool.path).isAbsolute)
    }

    @Test
    fun conversationsOnlyKeepsConfigId() {
        val snapshot = BackupSnapshot(
            conversations = listOf(conversation("c1").copy(configId = "cfg-kept")),
            modelsJson = """{"active_id":"other","prompt":"","configs":[{"id":"other"}]}""",
        )
        val result = inspected(archive(request(BackupGroup.Conversations), snapshot))
        val document = backupJson.decodeFromString(
            ConversationDocument.serializer(),
            result.texts.getValue("conversations"),
        )

        assertEquals("cfg-kept", document.conversations.single().configId)
        assertNull(result.summary.partitions["models"])
    }

    @Test
    fun newerFormatIsRejected() {
        val file = rawZip(
            mapOf("manifest.json" to """{"format_version":2,"secrets":{"mode":"later"}}"""),
        )

        val result = BackupExporter.inspect(file)

        assertEquals(RejectReason.NewerFormat, (result as InspectResult.Rejected).reason)
    }

    @Test
    fun unknownPartitionIsIgnored() {
        val file = rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/not_a_real_partition.json" to """{"hello":1}""",
                "partitions/masking_rules.json" to """{"rules":[]}""",
            ),
        )

        val result = BackupExporter.inspect(file) as InspectResult.Ok

        assertEquals(listOf("not_a_real_partition"), result.summary.ignoredPartitions)
        assertEquals(PartitionState.Empty, result.summary.partitions.getValue("masking_rules").state)
    }

    @Test
    fun pathContainingDotDotIsRejected() {
        val file = rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/../outside.txt" to "nope",
            ),
        )

        val result = BackupExporter.inspect(file)

        assertEquals(RejectReason.UnsafePath, (result as InspectResult.Rejected).reason)
    }

    @Test
    fun explicitEmptyMarkerSurvivesRoundTrip() {
        val raw = """{"rules":[],"_explicit":true}"""
        val result = inspected(
            archive(request(BackupGroup.ExecutionRules), BackupSnapshot(executionRulesJson = raw)),
        )

        assertEquals(raw, result.texts.getValue("execution_rules"))
    }

    @Test
    fun bakedDownloadDirBecomesPlaceholder() {
        val install = "/data/user/0/com.niki914.zafiro/files/downloads/py_install_apk"
        val download = "/data/user/0/com.niki914.zafiro/files/downloads/py_download_file"
        val snapshot = BackupSnapshot(
            pyToolsJson = """{"tools":[{"name":"py_install_apk","code":"p='$install'"},{"name":"py_download_file","code":"p='$download'"}]}""",
            pyInstallApkDir = install,
            pyDownloadFileDir = download,
        )
        val text = inspected(archive(request(BackupGroup.Tools), snapshot)).texts.getValue("py_tools")

        assertFalse(text.contains(install) || text.contains(download))
        assertTrue(
            text.contains("$DOWNLOADS_PLACEHOLDER/py_install_apk") &&
                text.contains("$DOWNLOADS_PLACEHOLDER/py_download_file"),
        )
    }

    @Test
    fun modifiedBuiltinSkillCarriesBundledSeed() {
        val current = "edited skill".toByteArray()
        val bundled = "bundled skill".toByteArray()
        val snapshot = BackupSnapshot(
            skills = listOf(
                SkillSnapshot(
                    id = "phone-use",
                    files = mapOf("SKILL.md" to current),
                    bundledSkillMd = bundled,
                ),
            ),
        )
        val file = archive(request(BackupGroup.Tools), snapshot)
        val seed = (readZip(file) as ZipRead.Ok)
            .entries.getValue("partitions/skills/phone-use/.seed")
            .toString(Charsets.UTF_8)

        assertEquals(sha256Hex(bundled), seed)
    }

    @Test
    fun unmodifiedBuiltinKeepsMatchingSeed() {
        val body = "same skill".toByteArray()
        val seed = sha256Hex(body)
        val snapshot = BackupSnapshot(
            skills = listOf(
                SkillSnapshot(
                    id = "termux",
                    files = mapOf("SKILL.md" to body, ".seed" to seed.toByteArray(Charsets.UTF_8)),
                    bundledSkillMd = body,
                ),
            ),
        )
        val packed = (readZip(archive(request(BackupGroup.Tools), snapshot)) as ZipRead.Ok)
            .entries.getValue("partitions/skills/termux/.seed")
            .toString(Charsets.UTF_8)

        assertEquals(seed, packed)
    }

    @Test
    fun onboardingIsExportedAndOtherDeviceFlagsStayOut() {
        val snapshot = BackupSnapshot(
            appStateJson = """{"onboarding_completed":true,"phone_use_consent_granted":true,"last_notified_update_version":"9","theme_mode":"dark"}""",
        )
        val prefs = parseObjectOrNull(
            inspected(archive(request(BackupGroup.Config), snapshot)).texts.getValue("preferences"),
        )!!

        assertEquals(true, (prefs["onboarding_completed"] as JsonPrimitive).booleanOrNull)
        assertTrue(prefs["phone_use_consent_granted"] == null && prefs["last_notified_update_version"] == null)
        assertEquals("dark", (prefs["theme_mode"] as JsonPrimitive).content)
    }

    @Test
    fun conversationBoundStateLeavesPreferences() {
        val state = """{"draft_text":"keep-this-draft","last_opened_conversation_id":"conv-9","pinned_conversations":[{"id":"pin-1","pinned_at":1}],"theme_mode":"dark"}"""
        val chats = inspected(archive(request(BackupGroup.Conversations), BackupSnapshot(appStateJson = state)))
        val document = backupJson.decodeFromString(
            ConversationDocument.serializer(),
            chats.texts.getValue("conversations"),
        )
        val preferences = inspected(
            archive(request(BackupGroup.Config), BackupSnapshot(appStateJson = state)),
        ).texts.getValue("preferences")

        assertEquals("keep-this-draft", document.draftText)
        assertEquals("conv-9", document.lastOpenedConversationId)
        assertTrue(
            document.pinnedConversations.toString().contains("pin-1") &&
                !preferences.contains("keep-this-draft") &&
                !preferences.contains("conv-9") &&
                !preferences.contains("pin-1"),
        )
    }

    @Test
    fun externalAttachmentPathsAreCountedNotPacked() {
        val path = "/storage/emulated/0/Download/report.pdf"
        val text = FilesBlock.block(listOf(FileRef(path)))!!
        val snapshot = BackupSnapshot(
            entries = listOf(entry("e1", Message.User(listOf(ContentBlock.Text(text))))),
        )
        val result = inspected(archive(request(BackupGroup.Conversations), snapshot))

        assertEquals(1, result.summary.externalAttachmentPaths)
        assertTrue(result.blobNames.isEmpty())
        assertTrue(result.texts.getValue("conversations").contains(path))
    }

    private fun allGroups(includeSecrets: Boolean = false): BackupRequest {
        return BackupRequest(
            groups = BackupGroup.entries.toSet(),
            includeSecrets = includeSecrets,
            createdAtMillis = 1L,
            appVersion = "1.10.1",
        )
    }

    private fun request(vararg groups: BackupGroup): BackupRequest {
        return BackupRequest(groups.toSet(), createdAtMillis = 1L, appVersion = "1.10.1")
    }

    private fun secretSnapshot(): BackupSnapshot {
        return BackupSnapshot(
            modelsJson = """{"active_id":"m","prompt":"","configs":[{"id":"m","endpoint":"https://example.test","api_key":"sk-live"}]}""",
            mcpServersJson = """{"servers":[{"name":"local","url":"http://127.0.0.1","headers":{"Authorization":"secret-token"}}]}""",
            mcpServiceJson = """{"enabled":true,"bearerToken":"bearer-live"}""",
        )
    }

    private fun archive(request: BackupRequest, snapshot: BackupSnapshot = BackupSnapshot()): File {
        val file = folder.newFile()
        BackupExporter.export(request, snapshot, file)
        return file
    }

    private fun inspected(file: File): InspectResult.Ok {
        return when (val result = BackupExporter.inspect(file)) {
            is InspectResult.Ok -> result
            is InspectResult.Rejected -> error("rejected ${result.reason}")
        }
    }

    private fun rawZip(entries: Map<String, String>): File {
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

    private fun messagesOf(result: InspectResult.Ok): List<Message> {
        val document = backupJson.decodeFromString(
            ConversationDocument.serializer(),
            result.texts.getValue("conversations"),
        )
        return document.entries.map { entry ->
            backupJson.decodeFromJsonElement(Message.serializer(), entry.message)
        }
    }

    private fun conversation(id: String): ConversationBackup {
        return ConversationBackup(id = id, title = id)
    }

    private fun entry(id: String, message: Message): EntryBackup {
        return EntryBackup(
            conversationId = "c1",
            id = id,
            messageJson = backupJson.encodeToString(Message.serializer(), message),
        )
    }

    private fun skill(id: String): SkillSnapshot {
        return SkillSnapshot(id = id, files = mapOf("SKILL.md" to "body-$id".toByteArray()))
    }
}
