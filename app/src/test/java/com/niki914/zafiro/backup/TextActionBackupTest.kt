// 保护：文本动作按 name 合并或覆盖。包里没有这个分区时不动本地；
// 空数组和带 _explicit 的空数组导回后仍是这份空文档，不会回到文件缺失时的两条内置动作。
package com.niki914.zafiro.backup

import com.niki914.store.StoreDescriptorRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextActionBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun absentPartitionLeavesLocalDocument() {
        val store = harness.memoryStore()
        store.textActionsJson = builtinActionsJson
        val file = harness.rawZip(
            mapOf(
                "manifest.json" to """{"format_version":1,"secrets":"stripped"}""",
                "partitions/mcp_servers.json" to """{"servers":[]}""",
            ),
        )

        harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(builtinActionsJson, store.textActionsJson)
    }

    @Test
    fun emptyArrayRoundTripStaysEmpty() {
        val store = harness.memoryStore()
        val empty = """{"actions":[]}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(textActionsJson = empty),
        )
        store.textActionsJson = builtinActionsJson

        harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(empty, store.textActionsJson)
        assertEquals(PartitionState.Empty, listShape(store.textActionsJson, BackupPartition.TextActions).state)
    }

    @Test
    fun explicitEmptyRoundTripStaysMarked() {
        val store = harness.memoryStore()
        val explicit = """{"actions":[],"_explicit":true}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(textActionsJson = explicit),
        )
        store.textActionsJson = builtinActionsJson

        harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(explicit, store.textActionsJson)
        assertEquals(
            PartitionState.ExplicitEmpty,
            listShape(store.textActionsJson, BackupPartition.TextActions).state,
        )
    }

    @Test
    fun mergeKeepsSameNameAndAddsNewName() {
        val store = harness.memoryStore()
        store.textActionsJson =
            """{"actions":[{"name":"Explain Screen","prompt":"local"},{"name":"OnlyLocal","prompt":"stay"}]}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(
                textActionsJson =
                    """{"actions":[{"name":"Explain Screen","prompt":"imported"},{"name":"Custom","prompt":"new"}]}""",
            ),
        )

        val report = harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Merge, store).succeeded()

        assertEquals(
            PartitionChangeCounts(added = 1, conflictSkipped = 1, untouched = 1),
            report.counts.getValue("text_actions"),
        )
        assertEquals(
            listOf("Explain Screen" to "local", "OnlyLocal" to "stay", "Custom" to "new"),
            actionPairs(store.textActionsJson),
        )
    }

    @Test
    fun overwriteReplacesSameNameAndDropsLocalOnly() {
        val store = harness.memoryStore()
        store.textActionsJson =
            """{"actions":[{"name":"Explain Screen","prompt":"local"},{"name":"OnlyLocal","prompt":"stay"}]}"""
        val imported =
            """{"actions":[{"name":"Explain Screen","prompt":"imported"},{"name":"Custom","prompt":"new"}]}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(textActionsJson = imported),
        )

        val report = harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(imported, store.textActionsJson)
        assertEquals(PartitionChangeCounts(added = 1, replaced = 1), report.counts.getValue("text_actions"))
    }

    private fun actionPairs(json: String): List<Pair<String, String>> {
        val actions = parseObjectOrNull(json)!!["actions"] as JsonArray
        return actions.map { element ->
            val obj = element as JsonObject
            (obj["name"] as JsonPrimitive).content to (obj["prompt"] as JsonPrimitive).content
        }
    }

    private val harness get() = BackupApplyHarness(folder)

    private companion object {
        val builtinActionsJson =
            StoreDescriptorRegistry.require(StoreDescriptorRegistry.TOOLS_TEXT_ACTIONS_ID).defaultJson
    }
}
