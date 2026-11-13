// 保护：导入是导出的逆过程。同 id 在合并时被盖掉、覆盖时把本地独有对话删掉、
// 没选的组被写进去、显式空被读成内置默认、引导完成状态没随偏好写回或旧包把本机的值抹掉、
// 电话同意和版本号被导入、同一张图再写一遍、
// 下载目录占位符留在工具代码里、.seed 没落盘、坏包仍然改本地、前面写成功的分区
// 在失败后不退回、快照超过三份或丢掉密钥，这些改动会让恢复结果和报告对不上。
package com.niki914.zafiro.backup

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
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

class BackupApplyTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun roundTripRestoresExportedRowsAndEmptyStates() {
        val store = harness.memoryStore()
        val image = File(store.imageDir(), "original.jpg")
        val bytes = byteArrayOf(1, 2, 3, 4)
        image.parentFile?.mkdirs()
        image.writeBytes(bytes)
        val snapshot = harness.roundTripSnapshot(store, image.absolutePath)
        val before = harness.canonical(snapshot)
        val file = harness.archive(harness.allGroups(includeSecrets = true), snapshot)

        store.clear()
        harness.apply(file, BackupGroup.entries.toSet(), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(before, harness.canonical(store.snapshot()))
    }

    @Test
    fun mergeKeepsLocalIdAndAddsNewId() {
        val store = harness.memoryStore()
        store.conversations = listOf(harness.conversation("a", "local-a"), harness.conversation("b", "local-b"))
        store.entries = listOf(harness.entry("a", "ea", "local-msg"))
        store.memoryJson = """{"memories":[{"id":"m1","content":"local"},{"id":"m2","content":"stay"}]}"""
        store.modelsJson = """{"active_id":"local-id","prompt":"old","configs":[{"id":"local-id"},{"id":"keep-id"}]}"""
        val incoming = BackupSnapshot(
            conversations = listOf(harness.conversation("a", "import-a"), harness.conversation("c", "import-c")),
            entries = listOf(harness.entry("a", "ea", "import-msg"), harness.entry("c", "ec", "import-c-msg")),
            memoryJson = """{"memories":[{"id":"m1","content":"import"},{"id":"m3","content":"new"}]}""",
            modelsJson = """{"active_id":"new-id","prompt":"fresh","configs":[{"id":"new-id"}]}""",
        )

        val report = harness.apply(
            harness.archive(harness.request(BackupGroup.Conversations, BackupGroup.Config), incoming),
            setOf(BackupGroup.Conversations, BackupGroup.Config),
            BackupStrategy.Merge,
            store,
        ).succeeded()

        assertEquals(
            mapOf(
                "conversations" to PartitionChangeCounts(added = 1, conflictSkipped = 1, untouched = 1),
                "memory" to PartitionChangeCounts(added = 1, conflictSkipped = 1, untouched = 1),
            ),
            report.counts.filterKeys { it == "conversations" || it == "memory" },
        )
        assertEquals(
            listOf("a" to "local-a", "b" to "local-b", "c" to "import-c"),
            store.conversations.map { it.id to it.title }.sortedBy { it.first },
        )
        assertEquals("local-msg", harness.messageText(store.entries.single { it.conversationId == "a" }))
        assertEquals("new-id", (parseObjectOrNull(store.modelsJson)!!["active_id"] as JsonPrimitive).content)
        assertEquals(
            listOf("keep-id", "local-id", "new-id"),
            harness.configIds(store.modelsJson).sorted(),
        )
    }

    @Test
    fun overwriteReplacesSameIdAndKeepsLocalConversation() {
        val store = harness.memoryStore()
        store.conversations = listOf(harness.conversation("keep", "stay"), harness.conversation("same", "old"))
        store.entries = listOf(harness.entry("keep", "ek", "stay-msg"), harness.entry("same", "es", "old-msg"))
        store.memoryJson = """{"memories":[{"id":"m-local","content":"only-here"},{"id":"m-same","content":"old"}]}"""
        val incoming = BackupSnapshot(
            conversations = listOf(harness.conversation("same", "new"), harness.conversation("added", "added")),
            entries = listOf(harness.entry("same", "es", "new-msg"), harness.entry("added", "ea", "added-msg")),
            memoryJson = """{"memories":[{"id":"m-same","content":"replaced"},{"id":"m-new","content":"fresh"}]}""",
        )

        val report = harness.apply(
            harness.archive(harness.request(BackupGroup.Conversations), incoming),
            setOf(BackupGroup.Conversations),
            BackupStrategy.Overwrite,
            store,
        ).succeeded()

        assertEquals(PartitionChangeCounts(added = 1, replaced = 1, untouched = 1), report.counts.getValue("conversations"))
        assertEquals(
            listOf("added" to "added", "keep" to "stay", "same" to "new"),
            store.conversations.map { it.id to it.title }.sortedBy { it.first },
        )
        assertEquals("stay-msg", harness.messageText(store.entries.single { it.conversationId == "keep" }))
        assertEquals("new-msg", harness.messageText(store.entries.single { it.conversationId == "same" }))
        assertFalse(store.memoryJson.contains("m-local"))
    }

    @Test
    fun absentGroupLeavesLocalUntouched() {
        val store = harness.memoryStore()
        store.conversations = listOf(harness.conversation("keep", "stay"))
        store.executionRulesJson = """{"rules":[],"_explicit":true}"""
        val beforeRules = store.executionRulesJson

        harness.apply(
            harness.archive(harness.request(BackupGroup.Tools), BackupSnapshot()),
            setOf(BackupGroup.Tools),
            BackupStrategy.Overwrite,
            store,
        ).succeeded()

        assertEquals(beforeRules, store.executionRulesJson)
        assertEquals("stay", store.conversations.single().title)
    }

    @Test
    fun explicitEmptyStaysExplicit() {
        val store = harness.memoryStore()
        store.executionRulesJson =
            """{"rules":[{"id":"local-rule","name":"Local","enabled_mode":"ALWAYS","patterns":["rm"]}]}"""

        harness.apply(
            harness.archive(
                harness.request(BackupGroup.ExecutionRules),
                BackupSnapshot(executionRulesJson = """{"rules":[],"_explicit":true}"""),
            ),
            setOf(BackupGroup.ExecutionRules),
            BackupStrategy.Overwrite,
            store,
        ).succeeded()

        assertEquals(
            PartitionState.ExplicitEmpty,
            listShape(store.executionRulesJson, BackupPartition.ExecutionRules).state,
        )
        assertFalse(store.executionRulesJson.contains("local-rule"))
    }

    @Test
    fun onboardingInTheFileReplacesLocal() {
        val store = harness.memoryStore()
        store.appStateJson =
            """{"onboarding_completed":false,"phone_use_consent_granted":false,"last_notified_update_version":"1","theme_mode":"light"}"""
        val file = harness.rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/preferences.json" to
                    """{"onboarding_completed":true,"phone_use_consent_granted":true,"last_notified_update_version":"9","theme_mode":"dark"}""",
            ),
        )

        harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Overwrite, store).succeeded()

        val state = parseObjectOrNull(store.appStateJson)!!
        assertEquals(
            listOf(true, false, "1", "dark"),
            listOf(
                (state["onboarding_completed"] as JsonPrimitive).booleanOrNull,
                (state["phone_use_consent_granted"] as JsonPrimitive).booleanOrNull,
                (state["last_notified_update_version"] as JsonPrimitive).content,
                (state["theme_mode"] as JsonPrimitive).content,
            ),
        )
    }

    @Test
    fun missingOnboardingInTheFileKeepsLocal() {
        val store = harness.memoryStore()
        store.appStateJson =
            """{"onboarding_completed":true,"phone_use_consent_granted":true,"last_notified_update_version":"9","theme_mode":"light"}"""
        val file = harness.rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/preferences.json" to """{"theme_mode":"dark"}""",
            ),
        )

        harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Overwrite, store).succeeded()

        val state = parseObjectOrNull(store.appStateJson)!!
        assertEquals(
            listOf(true, true, "9", "dark"),
            listOf(
                (state["onboarding_completed"] as JsonPrimitive).booleanOrNull,
                (state["phone_use_consent_granted"] as JsonPrimitive).booleanOrNull,
                (state["last_notified_update_version"] as JsonPrimitive).content,
                (state["theme_mode"] as JsonPrimitive).content,
            ),
        )
    }

    @Test
    fun referencedBlobRestoresPathAndExistingHashIsNotRewritten() {
        val store = harness.memoryStore()
        val kept = byteArrayOf(4, 5, 6)
        val fresh = byteArrayOf(7, 8, 9)
        val keptPath = File(store.imageDir(), "${sha256Hex(kept).take(8)}.jpg")
        keptPath.parentFile?.mkdirs()
        keptPath.writeBytes(kept)
        keptPath.setLastModified(1_000L)
        val snapshot = BackupSnapshot(
            conversations = listOf(harness.conversation("c1", "pics")),
            entries = listOf(
                harness.entry("c1", "e1", Message.User(listOf(ContentBlock.Image(keptPath.absolutePath, "image/jpeg")))),
                harness.entry("c1", "e2", Message.User(listOf(ContentBlock.Image(harness.freshFile(store, fresh), "image/jpeg")))),
            ),
            readImage = { path ->
                when (path) {
                    keptPath.absolutePath -> kept
                    else -> fresh
                }
            },
        )
        val extra = byteArrayOf(9, 9, 9, 9)
        val file = harness.withExtraBlob(harness.archive(harness.request(BackupGroup.Conversations), snapshot), extra)

        harness.apply(file, setOf(BackupGroup.Conversations), BackupStrategy.Overwrite, store).succeeded()

        val paths = store.entries.map { harness.imagePathOf(it.messageJson) }
        assertEquals(listOf(keptPath.absolutePath, harness.freshPath(store, fresh)), paths)
        assertEquals(1_000L, keptPath.lastModified())
        assertEquals(1, store.imagesWritten)
        assertFalse(File(store.imageDir(), "${sha256Hex(extra).take(8)}.jpg").exists())
    }

    @Test
    fun downloadPlaceholderBecomesLocalDir() {
        val store = harness.memoryStore()
        val snapshot = BackupSnapshot(
            pyToolsJson = """{"tools":[{"name":"py_install_apk","code":"p='${store.pyInstall}'"},{"name":"py_download_file","code":"p='${store.pyDownload}'"}]}""",
            pyInstallApkDir = store.pyInstall,
            pyDownloadFileDir = store.pyDownload,
        )
        val file = harness.archive(harness.request(BackupGroup.Tools), snapshot)

        file.inputStream().use { stream ->
            harness.apply(stream, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()
        }

        assertFalse(store.pyToolsJson.contains(DOWNLOADS_PLACEHOLDER))
        assertTrue(store.pyToolsJson.contains(store.pyInstall) && store.pyToolsJson.contains(store.pyDownload))
    }

    @Test
    fun skillSeedIsWrittenBack() {
        val store = harness.memoryStore()
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

        harness.apply(
            harness.archive(harness.request(BackupGroup.Tools), snapshot),
            setOf(BackupGroup.Tools),
            BackupStrategy.Overwrite,
            store,
        ).succeeded()

        assertEquals(
            sha256Hex(bundled),
            File(store.skillsRoot, "phone-use/.seed").readText(Charsets.UTF_8).trim(),
        )
        assertTrue(File(store.skillsRoot, "phone-use/SKILL.md").readBytes().contentEquals(current))
    }

    @Test
    fun rejectsGroupMissingFromTheFile() {
        val store = harness.memoryStore()
        store.conversations = listOf(harness.conversation("keep", "stay"))
        val before = store.marker()
        val file = harness.archive(harness.request(BackupGroup.Config), BackupSnapshot(appStateJson = """{"theme_mode":"dark"}"""))

        val result = harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store)

        assertEquals(ApplyRejectReason.GroupNotInArchive, (result as BackupApplyResult.Rejected).reason)
        assertEquals(before, store.marker())
        assertTrue(store.snapshots().isEmpty())
    }

    @Test
    fun rejectsNewerFormat() {
        val store = harness.memoryStore()
        val before = store.marker()
        val file = harness.rawZip(mapOf("manifest.json" to """{"format_version":2,"secrets":"stripped"}"""))

        val result = harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Merge, store)

        assertEquals(ApplyRejectReason.NewerFormat, (result as BackupApplyResult.Rejected).reason)
        assertEquals(before, store.marker())
    }

    @Test
    fun rejectsDotDotPath() {
        val store = harness.memoryStore()
        val before = store.marker()
        val file = harness.rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/../outside.txt" to "nope",
            ),
        )

        val result = harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Overwrite, store)

        assertEquals(ApplyRejectReason.UnsafePath, (result as BackupApplyResult.Rejected).reason)
        assertEquals(before, store.marker())
    }

    @Test
    fun failedPartitionRollsBackEarlierWrites() {
        val store = harness.memoryStore()
        store.conversations = listOf(harness.conversation("keep", "old"))
        store.memoryJson = """{"memories":[{"id":"m","content":"local"}]}"""
        val incoming = BackupSnapshot(
            conversations = listOf(harness.conversation("keep", "new"), harness.conversation("added", "added")),
            memoryJson = """{"memories":[{"id":"m","content":"imported"}]}""",
        )
        store.failPartition = BackupPartition.Memory

        val report = harness.apply(
            harness.archive(harness.request(BackupGroup.Conversations), incoming),
            setOf(BackupGroup.Conversations),
            BackupStrategy.Overwrite,
            store,
        ).reportOrThrow()

        assertEquals("memory" to true, report.failure?.partitionId to report.failure?.rolledBack)
        assertEquals(listOf("keep" to "old"), store.conversations.map { it.id to it.title })
        assertEquals("""{"memories":[{"id":"m","content":"local"}]}""", store.memoryJson)
    }

    @Test
    fun snapshotsKeepThreeAndIncludeSecrets() {
        val store = harness.memoryStore()
        val secret = """{"active_id":"m","prompt":"","configs":[{"id":"m","api_key":"sk-live"}]}"""
        store.modelsJson = secret
        val file = harness.archive(harness.request(BackupGroup.Config), store.snapshot(), includeSecrets = true)
        val names = mutableListOf<String>()

        repeat(4) {
            val report = harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Overwrite, store).succeeded()
            names += File(report.snapshotPath!!).name
        }

        val left = store.snapshots().map { it.name }.sorted()
        assertEquals(names.drop(1).sorted(), left)
        assertTrue(
            left.all { name ->
                val inspected = BackupExporter.inspect(File(store.snapshotsDir, name)) as InspectResult.Ok
                inspected.summary.secrets == SecretsMode.Included &&
                    inspected.texts.getValue("models").contains("sk-live")
            },
        )
    }

    @Test
    fun mcpServiceNeedsRestart() {
        val store = harness.memoryStore()
        store.mcpServiceJson = """{"enabled":true,"bearerToken":"local-token"}"""

        val report = harness.apply(
            harness.archive(harness.request(BackupGroup.Tools), BackupSnapshot()),
            setOf(BackupGroup.Tools),
            BackupStrategy.Overwrite,
            store,
        ).succeeded()

        assertEquals(listOf("mcp_service"), report.restartRequired)
        assertEquals("""{"enabled":false,"bearerToken":""}""", store.mcpServiceJson)
    }

    private val harness get() = BackupApplyHarness(folder)
}
